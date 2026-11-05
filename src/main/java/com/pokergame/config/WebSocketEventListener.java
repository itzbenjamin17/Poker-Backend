package com.pokergame.config;

import com.pokergame.security.PlayerPrincipal;
import com.pokergame.model.Room;
import com.pokergame.model.Game;
import com.pokergame.service.GameLifecycleService;
import com.pokergame.service.RoomService;
import com.pokergame.util.MdcUtils;
import com.pokergame.util.SecurityUtils;
import com.pokergame.service.event.RoomDestroyed;
import com.pokergame.service.event.RoomMemberRegistered;
import com.pokergame.service.event.RoomMembershipRetired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;

import java.security.Principal;
import java.time.Instant;
import com.pokergame.persistence.transaction.DurableMutationAspect;
import com.pokergame.util.KeyedLockRegistry;
import com.pokergame.util.LockLease;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;
import com.pokergame.security.RateLimitService;
import java.util.HashSet;

/**
 * Reconciles process-local WebSocket sessions with durable player membership.
 * <p>
 * Sessions and futures are intentionally transient, while disconnect deadlines and
 * resulting room/game mutations are durable. Per-player locks linearize reconnects
 * with grace-expiry cleanup so a player cannot be removed after reconnecting.
 * </p>
 */
@Component
public class WebSocketEventListener {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketEventListener.class);

    private final RoomService roomService;
    private final GameLifecycleService gameLifecycleService;
    private final RateLimitService rateLimitService;
    private final long disconnectGracePeriodMs;
    private final ConcurrentMap<String, Set<String>> activeSessionsByUser = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, PlayerPrincipal> sessionToPrincipal = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, PendingDisconnect> pendingDisconnects = new ConcurrentHashMap<>();
    private final KeyedLockRegistry<String> sessionLocks = new KeyedLockRegistry<>();
    private final TaskScheduler taskScheduler;
    private final DurableMutationAspect durableMutationAspect;

    /**
     * Creates the listener with the authoritative cleanup service and process-local
     * scheduler used to enforce reconnect grace.
     *
     * @param roomService              authoritative room registry
     * @param gameLifecycleService     durable game and cleanup service
     * @param rateLimitService         transient WebSocket rate-limit registry
     * @param taskScheduler            runtime grace-period scheduler
     * @param durableMutationAspect    per-room lock shared with durable mutations
     * @param disconnectGracePeriodMs allowed reconnect interval
     */
    public WebSocketEventListener(RoomService roomService,
            GameLifecycleService gameLifecycleService,
            RateLimitService rateLimitService,
            @Qualifier("taskScheduler") TaskScheduler taskScheduler,
            DurableMutationAspect durableMutationAspect,
            @Value("${poker.disconnect.grace-period-ms:120000}") long disconnectGracePeriodMs) {
        this.roomService = roomService;
        this.gameLifecycleService = gameLifecycleService;
        this.rateLimitService = rateLimitService;
        this.taskScheduler = taskScheduler;
        this.durableMutationAspect = durableMutationAspect;
        this.disconnectGracePeriodMs = disconnectGracePeriodMs;
    }
    
    /**
     * Manages the logic for when a user establishes a new WebSocket connection.
     * 
     * @param event The connect event.
     */
    @EventListener
    public void handleWebSocketConnectListener(SessionConnectEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        Principal principal = headerAccessor.getUser();
        String sessionId = headerAccessor.getSessionId();

        PlayerPrincipal playerPrincipal = SecurityUtils.getPlayerOrNull(principal);
        String roomId = playerPrincipal != null ? playerPrincipal.roomId() : null;
        String playerName = playerPrincipal != null ? playerPrincipal.playerName() : null;

        MdcUtils.runWithMdc(roomId, playerName, sessionId, () -> {
            if (playerPrincipal == null || sessionId == null) {
                logger.warn("WebSocket connect rejected: null playerPrincipal or sessionId (sessionId={})", sessionId);
                return;
            }

            registerSession(playerPrincipal, sessionId);
        });
    }

    /**
     * Registers an active session for the authenticated principal and reconciles domain state.
     *
     * @param playerPrincipal authenticated player principal
     * @param sessionId       active WebSocket session identity
     */
    public void registerSession(PlayerPrincipal playerPrincipal, String sessionId) {
        if (playerPrincipal == null || sessionId == null) {
            return;
        }
        String roomId = playerPrincipal.roomId();
        String playerName = playerPrincipal.playerName();
        String compositeName = playerKey(playerPrincipal);

        try (LockLease _ = acquireRoomLease(roomId);
                 LockLease _ = sessionLocks.acquire(compositeName)) {
            if (playerPrincipal.membershipId() != null && !playerPrincipal.membershipId().isBlank()) {
                Room room = roomService.getRoom(roomId);
                if (room != null) {
                    synchronized (room) {
                        if (!room.hasPlayer(playerName)
                                || !playerPrincipal.membershipId().equals(room.getMembershipId(playerName))) {
                            logger.debug("Ignoring WebSocket connect for stale membership of player {} in room {}",
                                    playerName, roomId);
                            return;
                        }
                    }
                }
            }
            // Registration and cleanup are linearized per player: a reconnect that wins
            // this lock cannot be evicted by an already-running grace timer.
            registerActiveSession(playerPrincipal, sessionId);
            // Reconcile Room lease and Game flags under Room -> Game.
            gameLifecycleService.markPlayerReconnected(roomId, playerName);

            PendingDisconnect pendingDisconnect = pendingDisconnects.remove(compositeName);
            if (pendingDisconnect != null) {
                pendingDisconnect.cancel();
                logger.info("Player {} reconnected to room {} before grace expiry", playerName, roomId);
            }
        }
    }

    /**
     * Manages the logic for when a user disconnects from the WebSocket.
     * 
     * @param event The disconnect event.
     */
    @EventListener
    public void handleWebSocketDisconnectListener(SessionDisconnectEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = headerAccessor.getSessionId();
        
        // Recover principal from session storage if not present in the event
        PlayerPrincipal principal = SecurityUtils.getPlayerOrNull(headerAccessor.getUser());
        if (principal == null && sessionId != null) {
            principal = sessionToPrincipal.get(sessionId);
        }
        final PlayerPrincipal recoveredPrincipal = principal;

        String roomId = recoveredPrincipal != null ? recoveredPrincipal.roomId() : null;
        String playerName = recoveredPrincipal != null ? recoveredPrincipal.playerName() : null;

        MdcUtils.runWithMdc(roomId, playerName, sessionId, () -> {
            if (recoveredPrincipal == null) {
                logger.debug("WebSocket disconnected without resolvable principal. sessionId={}", sessionId);
                return;
            }

            final PlayerPrincipal playerPrincipal = recoveredPrincipal;
            String compositeName = playerKey(playerPrincipal);
            
            try (LockLease _ = acquireRoomLease(roomId);
                 LockLease _ = sessionLocks.acquire(compositeName)) {
                if (playerPrincipal.membershipId() != null && !playerPrincipal.membershipId().isBlank()) {
                    Room currentRoom = roomService.getRoom(roomId);
                    if (currentRoom != null) {
                        synchronized (currentRoom) {
                            if (!currentRoom.hasPlayer(playerName)
                                    || !playerPrincipal.membershipId().equals(currentRoom.getMembershipId(playerName))) {
                                unregisterActiveSession(playerPrincipal, sessionId);
                                logger.debug("Ignoring WebSocket disconnect for stale membership of player {} in room {}",
                                        playerName, roomId);
                                return;
                            }
                        }
                    }
                }
                // Users may have multiple sessions (e.g. multiple browser tabs),
                // So we only schedule a clean-up if there are no active sessions left
                boolean removed = unregisterActiveSession(playerPrincipal, sessionId);
                if (!removed) {
                    logger.debug("Disconnect ignored for session {} of user {} (unregistered, duplicate, or mismatched)",
                            sessionId, compositeName);
                    return;
                }
                if (hasActiveSession(compositeName)) {
                    logger.debug("User {} still has another active session; skipping disconnect timer", compositeName);
                    return;
                }

                long disconnectDeadlineEpochMs = System.currentTimeMillis() + disconnectGracePeriodMs;
                Instant scheduleInstant = Instant.ofEpochMilli(disconnectDeadlineEpochMs);

                Room room = roomService.getRoom(roomId);
                String membershipId = null;
                if (room != null) {
                    synchronized (room) {
                        if (room.hasPlayer(playerName)) {
                            room.setPlayerConnected(playerName, false);
                            room.setAbsenceDeadline(playerName, disconnectDeadlineEpochMs);
                        }
                        membershipId = room.getMembershipId(playerName);
                    }
                }

                boolean gameActive = gameLifecycleService.gameExists(roomId);
                try {
                    if (gameActive && gameLifecycleService.playerExistsInGame(roomId, playerName)) {
                        gameLifecycleService.markPlayerDisconnected(roomId, playerName, disconnectDeadlineEpochMs);
                    }
                } catch (Exception ex) {
                    logger.warn("Failed to mark player {} disconnected in game {}: {}", playerName, roomId, ex.getMessage());
                }

                PendingDisconnect existing = pendingDisconnects.remove(compositeName);
                if (existing != null) {
                    existing.cancel();
                }

                logger.info("WebSocket disconnected for user {}. Scheduling delayed cleanup ({} ms)",
                        compositeName, disconnectGracePeriodMs);

                PendingDisconnect registration = new PendingDisconnect(membershipId, disconnectDeadlineEpochMs);
                pendingDisconnects.put(compositeName, registration);
                ScheduledFuture<?> future = taskScheduler.schedule(
                        () -> cleanupDisconnectedUser(playerPrincipal, registration),
                        scheduleInstant);
                registration.attach(future);
            }
        });
    }

    /**
     * Cleans up a disconnected user from the game.
     * 
     * @param principal The PlayerPrincipal of the disconnected user.
     */
    private void cleanupDisconnectedUser(PlayerPrincipal principal, PendingDisconnect expected) {
        String compositeName = playerKey(principal);
        String playerName = principal.playerName();
        String roomId = principal.roomId();
        
        try (LockLease _ = acquireRoomLease(roomId);
                 LockLease _ = sessionLocks.acquire(compositeName)) {
            if (pendingDisconnects.get(compositeName) != expected || expected.isCancelled()) return;

            if (hasActiveSession(compositeName)) {
                logger.debug("Skipping disconnect cleanup for user {} because an active session exists", compositeName);
                pendingDisconnects.remove(compositeName, expected);
                return;
            }
            pendingDisconnects.remove(compositeName, expected);

            logger.info("WebSocket grace period expired. Removing disconnected user: {}", compositeName);
            rateLimitService.cleanUpWs(compositeName);

            Room room = roomService.getRoom(roomId);
            boolean playerInRoom = false;
            if (room != null) {
                synchronized (room) {
                    playerInRoom = room.hasPlayer(playerName);
                }
            }
            boolean playerInGame = gameLifecycleService.gameExists(roomId)
                    && gameLifecycleService.playerExistsInGame(roomId, playerName);
            if (!playerInRoom && !playerInGame) {
                return;
            }

            try {
                String roomName = room != null ? room.getRoomName() : roomId;
                logger.info("Automatically removing disconnected user '{}' from room '{}'", playerName, roomName);
                gameLifecycleService.removeDisconnectedPlayer(roomId, playerName, expected.membershipId(), expected.deadlineEpochMs());
            } catch (Exception e) {
                String roomName = room != null ? room.getRoomName() : roomId;
                logger.error("Failed to remove disconnected user '{}' from room '{}'", playerName, roomName, e);
            }
        }
    }

    /**
     * Registers a first-connection grace period for a newly created or joined room membership.
     * Invoked after the membership mutation is accepted.
     * Validates room instance and membership identity, checks active sockets,
     * and avoids overwriting intervening connect/disconnect transitions.
     *
     * @param roomId         room identity
     * @param playerName     member identity
     * @param membershipId   opaque process-local membership identity from Room.addPlayer
     * @param deadlineEpochMs original acceptance deadline
     */
    public void registerInitialConnection(String roomId, String playerName, String membershipId, long deadlineEpochMs) {
        PlayerPrincipal principal = new PlayerPrincipal(playerName, roomId);
        String compositeName = playerKey(principal);
        MdcUtils.runWithMdc(roomId, playerName, () -> {
            try (LockLease _ = acquireRoomLease(roomId);
                 LockLease _ = sessionLocks.acquire(compositeName)) {
                Room room = roomService.getRoom(roomId);
                if (room == null) {
                    return;
                }
                String currentMemId;
                boolean playerConnected;
                synchronized (room) {
                    if (!room.hasPlayer(playerName)) {
                        return;
                    }
                    currentMemId = room.getMembershipId(playerName);
                    playerConnected = room.isPlayerConnected(playerName);
                }
                if (!Objects.equals(membershipId, currentMemId)) {
                    return;
                }

                if (hasActiveSession(compositeName) || playerConnected) {
                    logger.debug("Player {} in room {} already connected; skipping initial disconnect registration",
                            playerName, roomId);
                    return;
                }

                PendingDisconnect existing = pendingDisconnects.get(compositeName);
                if (existing != null) {
                    return;
                }

                PendingDisconnect registration = new PendingDisconnect(membershipId, deadlineEpochMs);
                pendingDisconnects.put(compositeName, registration);
                ScheduledFuture<?> future = taskScheduler.schedule(
                        () -> cleanupDisconnectedUser(principal, registration),
                        Instant.ofEpochMilli(deadlineEpochMs));
                registration.attach(future);
                logger.info("Registered initial connection allowance for {} in room {} until {}",
                        playerName, roomId, deadlineEpochMs);
            }
        });
    }

    /**
     * Rebuilds only the runtime cleanup timer for a player restored from durable
     * state. Session identifiers and rate-limit buckets intentionally remain empty
     * because they identify the previous process, not durable player state.
     *
     * @param roomId          restored room identity
     * @param playerName      restored player awaiting reconnection
     * @param deadlineEpochMs absolute cleanup deadline
     */
    public void scheduleRecoveredDisconnect(String roomId, String playerName, long deadlineEpochMs) {
        PlayerPrincipal principal = new PlayerPrincipal(playerName, roomId);
        String compositeName = playerKey(principal);
        long delay = Math.max(0, deadlineEpochMs - System.currentTimeMillis());
        logger.info("Scheduling recovered disconnect for player {} in room {} with delayMs={}", playerName, roomId, delay);
        MdcUtils.runWithMdc(roomId, playerName, () -> {
            try (LockLease _ = acquireRoomLease(roomId);
                 LockLease _ = sessionLocks.acquire(compositeName)) {
                PendingDisconnect existing = pendingDisconnects.remove(compositeName);
                if (existing != null) existing.cancel();
                Room restoredRoom = roomService.getRoom(roomId);
                if (hasActiveSession(compositeName)) {
                    try {
                        gameLifecycleService.markPlayerReconnected(roomId, playerName);
                    } catch (Exception e) {
                        logger.warn("Failed to mark player '{}' reconnected in room '{}': {}", playerName, roomId, e.getMessage());
                    }
                    return;
                }
                String restoredMembershipId = null;
                if (restoredRoom != null) {
                    synchronized (restoredRoom) {
                        if (restoredRoom.hasPlayer(playerName)) {
                            restoredRoom.setPlayerConnected(playerName, false);
                            restoredRoom.setAbsenceDeadline(playerName, deadlineEpochMs);
                        }
                        restoredMembershipId = restoredRoom.getMembershipId(playerName);
                    }
                }
                PendingDisconnect registration = new PendingDisconnect(restoredMembershipId, deadlineEpochMs);
                pendingDisconnects.put(compositeName, registration);
                ScheduledFuture<?> future = taskScheduler.schedule(
                        () -> cleanupDisconnectedUser(principal, registration), Instant.ofEpochMilli(deadlineEpochMs));
                registration.attach(future);
            }
        });
    }

    /**
     * Logs STOMP topic subscriptions at debug level with MDC correlation.
     *
     * @param event the subscribe event
     */
    @EventListener
    public void handleSubscribe(SessionSubscribeEvent event) {
        StompHeaderAccessor h = StompHeaderAccessor.wrap(event.getMessage());
        PlayerPrincipal p = SecurityUtils.getPlayerOrNull(h.getUser());
        String roomId = p != null ? p.roomId() : null;
        String playerName = p != null ? p.playerName() : null;
        MdcUtils.runWithMdc(roomId, playerName, h.getSessionId(), () -> {
            logger.debug("STOMP subscribe: user={} destination={} session={}",
                    p == null ? "anonymous" : p.getName(), h.getDestination(), h.getSessionId());
        });
    }

    /**
     * Logs STOMP topic unsubscribes at debug level with MDC correlation.
     *
     * @param event the unsubscribe event
     */
    @EventListener
    public void handleUnsubscribe(SessionUnsubscribeEvent event) {
        StompHeaderAccessor h = StompHeaderAccessor.wrap(event.getMessage());
        PlayerPrincipal p = SecurityUtils.getPlayerOrNull(h.getUser());
        String roomId = p != null ? p.roomId() : null;
        String playerName = p != null ? p.playerName() : null;
        MdcUtils.runWithMdc(roomId, playerName, h.getSessionId(), () -> {
            logger.debug("STOMP unsubscribe: user={} subscriptionId={} session={}",
                    p == null ? "anonymous" : p.getName(), h.getSubscriptionId(), h.getSessionId());
        });
    }


    /**
     * Registers an active WebSocket session for the given user.
     * 
     * @param principal The PlayerPrincipal of the user.
     * @param sessionId The session ID of the user.
     */
    private void registerActiveSession(PlayerPrincipal principal, String sessionId) {
        String compositeName = playerKey(principal);
        // Get the set of active sessions for the user or create a new one if it
        // doesn't exist
        Set<String> sessions = activeSessionsByUser.computeIfAbsent(compositeName, k -> ConcurrentHashMap.newKeySet());
        // A delayed retirement intent must not let sockets from an older membership
        // keep the replacement connected after its own last socket closes.
        sessions.removeIf(existingSession -> {
            PlayerPrincipal existing = sessionToPrincipal.get(existingSession);
            boolean obsolete = existing == null || !Objects.equals(existing.membershipId(), principal.membershipId());
            if (obsolete && existing != null) sessionToPrincipal.remove(existingSession, existing);
            return obsolete;
        });
        sessions.add(sessionId);

        // Map the session ID back to the principal for recovery on disconnect
        sessionToPrincipal.put(sessionId, principal);
    }

    /**
     * Unregisters an active WebSocket session for the given user.
     * 
     * @param compositeName The composite name of the user.
     * @param sessionId The session ID of the user.
     */
    private boolean unregisterActiveSession(PlayerPrincipal principal, String sessionId) {
        if (principal == null || sessionId == null) { return false; }
        PlayerPrincipal registeredPrincipal = sessionToPrincipal.get(sessionId);
        if (registeredPrincipal == null || !principal.equals(registeredPrincipal)) { return false; }
        String compositeName = playerKey(principal);
        Set<String> sessions = activeSessionsByUser.get(compositeName);
        boolean removed = sessions != null && sessions.remove(sessionId);
        if (removed) { sessionToPrincipal.remove(sessionId); }
        if (sessions != null && sessions.isEmpty()) { activeSessionsByUser.remove(compositeName); }
        return removed;
    }


    /**
     * Checks if the given user has any active WebSocket sessions.
     * 
     * @param username The username of the user.
     * @return True if the user has any active WebSocket sessions, false otherwise.
     */
    private boolean hasActiveSession(String username) {
        Set<String> sessions = activeSessionsByUser.get(username);
        return sessions != null && !sessions.isEmpty();
    }

    /**
     * A scheduled grace-period cleanup for one player membership in one room.
     * Reconnect handling cancels it, and the cleanup callback checks that this exact
     * instance is still the registered one (by identity) before removing anyone, so
     * a superseded or cancelled timer cannot act.
     */
    private static final class PendingDisconnect {
        private final String membershipId;
        private final long deadlineEpochMs;
        private volatile ScheduledFuture<?> future;
        private volatile boolean cancelled;

        private PendingDisconnect(String membershipId, long deadlineEpochMs) {
            this.membershipId = membershipId;
            this.deadlineEpochMs = deadlineEpochMs;
        }

        String membershipId() { return membershipId; }
        long deadlineEpochMs() { return deadlineEpochMs; }
        boolean isCancelled() { return cancelled; }

        void attach(ScheduledFuture<?> scheduled) {
            future = scheduled;
            if (cancelled && scheduled != null) scheduled.cancel(false);
        }

        void cancel() {
            cancelled = true;
            ScheduledFuture<?> scheduled = future;
            if (scheduled != null) scheduled.cancel(false);
        }
    }

    /**
     * Acquires the outer durable room lease before any player lease or aggregate monitor.
     * This ordering also applies to reconnects, grace callbacks, and scheduled cleanups.
     *
     * @param roomId room whose session state is changing
     * @return room lease, or a no-op lease when persistence is disabled
     */
    private LockLease acquireRoomLease(String roomId) {
        return durableMutationAspect.acquireRoomLock(roomId);
    }

    /**
     * Returns the room/player key used only for runtime connection tracking.
     * Spring private routing uses the separate membership-bound principal name.
     *
     * @param principal player being tracked
     * @return stable room/player connection key
     */
    private static String playerKey(PlayerPrincipal principal) {
        return principal.playerName() + ":" + principal.roomId();
    }

    /** Schedules cleanup scoped to the retired membership, preserving any replacement. */
    public void publishMembershipRetired(String roomId, String playerName, String membershipId) {
        if (roomId == null || playerName == null) return;
        scheduleCleanup("membership retirement", () -> processMembershipRetired(roomId, playerName, membershipId));
    }

    /** Schedules removal of all transient sessions and deadlines for a destroyed room. */
    public void publishRoomDestroyed(String roomId) {
        if (roomId == null) return;
        scheduleCleanup("room destruction", () -> processRoomDestroyed(roomId));
    }

    /**
     * Starts the first-connection grace period for an accepted room membership.
     *
     * @param event accepted create or join
     */
    @EventListener
    public void onRoomMemberRegistered(RoomMemberRegistered event) {
        registerInitialConnection(event.roomId(), event.playerName(), event.membershipId(), event.deadlineEpochMs());
    }

    /**
     * Schedules transient cleanup for a retired membership.
     *
     * @param event accepted departure
     */
    @EventListener
    public void onRoomMembershipRetired(RoomMembershipRetired event) {
        publishMembershipRetired(event.roomId(), event.playerName(), event.membershipId());
    }

    /**
     * Schedules transient cleanup for a destroyed room.
     *
     * @param event accepted room removal
     */
    @EventListener
    public void onRoomDestroyed(RoomDestroyed event) {
        publishRoomDestroyed(event.roomId());
    }

    /**
     * Runs transient-state cleanup on the scheduler so the publisher, which still holds
     * the room lease while after-commit callbacks run, is not delayed by it. The cleanup
     * takes the room lease itself and so runs once the publishing mutation has released it.
     * A rejected task (scheduler shut down) is dropped: this state is process-local and
     * disappears with the process.
     *
     * @param description what is being cleaned up, for logging
     * @param cleanup     cleanup to run
     */
    private void scheduleCleanup(String description, Runnable cleanup) {
        try {
            taskScheduler.schedule(() -> {
                try {
                    cleanup.run();
                } catch (Exception e) {
                    logger.warn("Error processing {} cleanup", description, e);
                }
            }, Instant.now());
        } catch (TaskRejectedException e) {
            logger.warn("Scheduler rejected {} cleanup; dropping it", description);
        }
    }

    /** Retires matching membership state under room lease, player lease, then Room/Game monitors. */
    private void processMembershipRetired(String roomId, String playerName, String membershipId) {
        String compositeName = playerName + ":" + roomId;
        try (LockLease _ = acquireRoomLease(roomId)) {
            try (LockLease _ = sessionLocks.acquire(compositeName)) {
                Room room = roomService.getRoom(roomId);
                if (room != null) {
                    synchronized (room) {
                        if (room.hasPlayer(playerName)) {
                            String currentMemId = room.getMembershipId(playerName);
                            if (!Objects.equals(membershipId, currentMemId)) {
                                return;
                            }
                        }
                        Game game = gameLifecycleService.getGame(roomId);
                        if (game != null) {
                            synchronized (game) {
                                retireMembershipUnderLock(roomId, playerName, membershipId, compositeName);
                            }
                        } else {
                            retireMembershipUnderLock(roomId, playerName, membershipId, compositeName);
                        }
                    }
                } else {
                    Game game = gameLifecycleService.getGame(roomId);
                    if (game != null) {
                        synchronized (game) {
                            retireMembershipUnderLock(roomId, playerName, membershipId, compositeName);
                        }
                    } else {
                        retireMembershipUnderLock(roomId, playerName, membershipId, compositeName);
                    }
                }
            }
        }
    }

    /** Removes transient room state under the same room-before-player lease order as reconnects. */
    private void processRoomDestroyed(String roomId) {
        LockLease roomLease = durableMutationAspect.acquireRoomLock(roomId);
        try (roomLease) {
            gameLifecycleService.cancelRuntimeTasks(roomId, false);
            Set<String> usersToClean = new HashSet<>();
            for (String user : activeSessionsByUser.keySet()) {
                if (user.endsWith(":" + roomId)) {
                    usersToClean.add(user);
                }
            }
            for (String user : pendingDisconnects.keySet()) {
                if (user.endsWith(":" + roomId)) {
                    usersToClean.add(user);
                }
            }
            for (String user : usersToClean) {
                String playerName = user.substring(0, user.length() - roomId.length() - 1);
                try (LockLease _ = sessionLocks.acquire(user)) {
                    retireMembershipUnderLock(roomId, playerName, null, user);
                }
            }
        }
    }

    private void retireMembershipUnderLock(String roomId, String playerName, String membershipId, String compositeName) {
        PendingDisconnect pending = pendingDisconnects.get(compositeName);
        if (pending != null && (membershipId == null || Objects.equals(pending.membershipId(), membershipId))) {
            pending.cancel();
            pendingDisconnects.remove(compositeName, pending);
        }
        Set<String> sessions = activeSessionsByUser.remove(compositeName);
        if (sessions != null) {
            for (String sessionId : sessions) {
                sessionToPrincipal.remove(sessionId);
            }
        }
        rateLimitService.cleanUpWs(compositeName);
    }

    /**
     * Returns the number of active sessions for the specified composite user name.
     *
     * @param compositeName composite user name (roomId:playerName)
     * @return number of active sessions
     */
    public int getActiveSessionCount(String compositeName) {
        Set<String> sessions = activeSessionsByUser.get(compositeName);
        return sessions != null ? sessions.size() : 0;
    }

    /**
     * Checks whether a pending disconnect grace timer is currently registered for the user.
     *
     * @param compositeName composite user name (roomId:playerName)
     * @return {@code true} if a disconnect is pending, {@code false} otherwise
     */
    public boolean hasPendingDisconnect(String compositeName) {
        return pendingDisconnects.containsKey(compositeName);
    }

    /**
     * Returns the count of active keys currently held or reserved in the session lock registry.
     *
     * @return number of active lock keys
     */
    public int getActiveSessionLockCount() {
        return sessionLocks.activeKeyCount();
    }

    /**
     * Returns the count of pending disconnect registrations.
     *
     * @return number of pending disconnects
     */
    public int getPendingDisconnectCount() {
        return pendingDisconnects.size();
    }

    /**
     * Returns the count of tracked session-to-principal mappings.
     *
     * @return number of mapped sessions
     */
    public int getSessionToPrincipalCount() {
        return sessionToPrincipal.size();
    }
}
