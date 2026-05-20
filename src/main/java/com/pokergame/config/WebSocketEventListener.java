package com.pokergame.config;

import com.pokergame.security.PlayerPrincipal;
import com.pokergame.model.Room;
import com.pokergame.service.GameLifecycleService;
import com.pokergame.service.RoomService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;

import java.security.Principal;
import java.time.Instant;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;

@Component
public class WebSocketEventListener {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketEventListener.class);

    private final RoomService roomService;
    private final GameLifecycleService gameLifecycleService;
    private final com.pokergame.security.RateLimitService rateLimitService;
    private final long disconnectGracePeriodMs;
    private final ConcurrentMap<String, Set<String>> activeSessionsByUser = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, PlayerPrincipal> sessionToPrincipal = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, PendingDisconnect> pendingDisconnects = new ConcurrentHashMap<>();
    private final TaskScheduler taskScheduler;

    @Autowired
    public WebSocketEventListener(RoomService roomService,
            GameLifecycleService gameLifecycleService,
            com.pokergame.security.RateLimitService rateLimitService,
            @Qualifier("taskScheduler") TaskScheduler taskScheduler,
            @Value("${poker.disconnect.grace-period-ms:120000}") long disconnectGracePeriodMs) {
        this.roomService = roomService;
        this.gameLifecycleService = gameLifecycleService;
        this.rateLimitService = rateLimitService;
        this.taskScheduler = taskScheduler;
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

        if (!(principal instanceof PlayerPrincipal playerPrincipal) || sessionId == null) {
            return;
        }

        String compositeName = playerPrincipal.getName();
        String playerName = playerPrincipal.playerName();
        String roomId = playerPrincipal.roomId();

        // Tracks specific session to user because a user can have multiple sessions (e.g. multiple browser tabs)
        registerActiveSession(playerPrincipal, sessionId);
        logger.debug("Registered active WebSocket session {} for user {}", sessionId, compositeName);

        // If the user was previously disconnected, cancel the clean-up task and mark them as reconnected
        PendingDisconnect pendingDisconnect = pendingDisconnects.remove(compositeName);
        if (pendingDisconnect != null) {
            pendingDisconnect.future().cancel(false);

            if (gameLifecycleService.gameExists(roomId)
                    && gameLifecycleService.playerExistsInGame(roomId, playerName)) {
                gameLifecycleService.markPlayerReconnected(roomId, playerName);
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
        PlayerPrincipal recoveredPrincipal = null;
        if (headerAccessor.getUser() instanceof PlayerPrincipal pp) {
            recoveredPrincipal = pp;
        } else if (sessionId != null) {
            recoveredPrincipal = sessionToPrincipal.get(sessionId);
        }

        if (recoveredPrincipal == null) {
            logger.debug("WebSocket disconnected without resolvable principal. sessionId={}", sessionId);
            return;
        }

        final PlayerPrincipal playerPrincipal = recoveredPrincipal;
        String compositeName = playerPrincipal.getName();
        
        // Users may have multiple sessions (e.g. multiple browser tabs),
        // So we only schedule a clean-up if there are no active sessions left
        unregisterActiveSession(compositeName, sessionId);
        if (hasActiveSession(compositeName)) {
            logger.debug("User {} still has another active session; skipping disconnect timer", compositeName);
            return;
        }

        String playerName = playerPrincipal.playerName();
        String roomId = playerPrincipal.roomId();

        // Marks the player as disconnected and marks when they should be removed from a game
        boolean gameActive = gameLifecycleService.gameExists(roomId);
        long disconnectDeadlineEpochMs = System.currentTimeMillis() + disconnectGracePeriodMs;
        if (gameActive && gameLifecycleService.playerExistsInGame(roomId, playerName)) {
            gameLifecycleService.markPlayerDisconnected(roomId, playerName, disconnectDeadlineEpochMs);
        }

        // Cancel any existing disconnect timer for this user
        PendingDisconnect existing = pendingDisconnects.remove(compositeName);
        if (existing != null) {
            existing.future().cancel(false);
        }

        logger.info("WebSocket disconnected for user {}. Scheduling delayed cleanup ({} ms)",
                compositeName, disconnectGracePeriodMs);

        // Schedule the user to be removed from the game if they don't reconnect in 2 minutes
        ScheduledFuture<?> future = taskScheduler.schedule(
                () -> cleanupDisconnectedUser(playerPrincipal),
                Instant.now().plusMillis(disconnectGracePeriodMs));

        pendingDisconnects.put(compositeName, new PendingDisconnect(roomId, future));
    }

    /**
     * Cleans up a disconnected user from the game.
     * 
     * @param principal The PlayerPrincipal of the disconnected user.
     */
    private void cleanupDisconnectedUser(PlayerPrincipal principal) {
        String compositeName = principal.getName();
        String playerName = principal.playerName();
        String roomId = principal.roomId();
        
        pendingDisconnects.remove(compositeName);

        if (hasActiveSession(compositeName)) {
            logger.debug("Skipping disconnect cleanup for user {} because an active session exists", compositeName);
            return;
        }

        logger.info("WebSocket grace period expired. Removing disconnected user: {}", compositeName);

        // Also clean up their WebSocket rate limit bucket
        rateLimitService.cleanUpWs(compositeName);

        Room room = roomService.getRoom(roomId);
        if (room == null || !room.hasPlayer(playerName)) {
            return;
        }

        try {
            logger.info("Automatically removing disconnected user '{}' from room '{}'", playerName,
                    room.getRoomName());
            boolean gameActive = gameLifecycleService.gameExists(roomId);
            roomService.leaveRoom(roomId, playerName, !gameActive);

            if (gameActive && gameLifecycleService.playerExistsInGame(roomId, playerName)) {
                gameLifecycleService.leaveGame(roomId, playerName);
            }
        } catch (Exception e) {
            logger.error("Failed to remove disconnected user '{}' from room '{}'", playerName,
                    room.getRoomName(), e);
        }
    }

    /**
     * Finds the room ID of the room that the given player is in.
     * 
     * @param username The username of the player.
     * @return The room ID of the room that the given player is in, or null if the player is not in any room.
     */
    // TODO: Make this more efficient, maybe store the room ID in the user object
    private String findRoomIdByPlayer(String username) {
        for (Room room : roomService.getRooms()) {
            if (room.hasPlayer(username)) {
                return room.getRoomId();
            }
        }
        return null;
    }

    /**
     * Registers an active WebSocket session for the given user.
     * 
     * @param principal The PlayerPrincipal of the user.
     * @param sessionId The session ID of the user.
     */
    private void registerActiveSession(PlayerPrincipal principal, String sessionId) {
        String compositeName = principal.getName();
        // Get the set of active sessions for the user or create a new one if it
        // doesn't exist
        Set<String> sessions = activeSessionsByUser.computeIfAbsent(compositeName, k -> ConcurrentHashMap.newKeySet());
        // Add the new session ID to the user's set of sessions
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
    private void unregisterActiveSession(String compositeName, String sessionId) {
        if (sessionId != null) {
            sessionToPrincipal.remove(sessionId);
        }

        Set<String> sessions = activeSessionsByUser.getOrDefault(compositeName, Collections.emptySet());
        if (sessionId != null) {
            sessions.remove(sessionId);
        }

        if (sessions.isEmpty()) {
            activeSessionsByUser.remove(compositeName);
        }
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

    private record PendingDisconnect(String roomId, ScheduledFuture<?> future) {
    }
}