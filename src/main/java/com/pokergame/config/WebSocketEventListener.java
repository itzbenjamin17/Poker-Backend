package com.pokergame.config;

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
    private final long disconnectGracePeriodMs;
    private final ConcurrentMap<String, Set<String>> activeSessionsByUser = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> sessionToUser = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, PendingDisconnect> pendingDisconnects = new ConcurrentHashMap<>();
    private final TaskScheduler taskScheduler;

    @Autowired
    public WebSocketEventListener(RoomService roomService,
            GameLifecycleService gameLifecycleService,
            @Qualifier("taskScheduler") TaskScheduler taskScheduler,
            @Value("${poker.disconnect.grace-period-ms:120000}") long disconnectGracePeriodMs) {
        this.roomService = roomService;
        this.gameLifecycleService = gameLifecycleService;
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
        Principal user = headerAccessor.getUser();
        String sessionId = headerAccessor.getSessionId();

        if (user == null || sessionId == null) {
            return;
        }

        String username = user.getName();
        // Tracks specific session to user because a user can have multiple sessions (e.g. multiple browser tabs)
        registerActiveSession(username, sessionId);
        logger.debug("Registered active WebSocket session {} for user {}", sessionId, username);

        // If the user was previously disconnected, cancel the cleanup task and mark them as reconnected
        PendingDisconnect pendingDisconnect = pendingDisconnects.remove(username);
        if (pendingDisconnect != null) {
            pendingDisconnect.future().cancel(false);

            if (gameLifecycleService.gameExists(pendingDisconnect.roomId())
                    && gameLifecycleService.playerExistsInGame(pendingDisconnect.roomId(), username)) {
                gameLifecycleService.markPlayerReconnected(pendingDisconnect.roomId(), username);
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
        Principal user = headerAccessor.getUser();
        String sessionId = headerAccessor.getSessionId();

        String username = user != null ? user.getName() : sessionId != null ? sessionToUser.get(sessionId) : null;

        if (username == null) {
            logger.debug("WebSocket disconnected without resolvable username. sessionId={}", sessionId);
            return;
        }
        // Users may have multiple sessions (e.g. multiple browser tabs)
        // So we only schedule a cleanup if there are no active sessions left
        unregisterActiveSession(username, sessionId);
        if (hasActiveSession(username)) {
            logger.debug("User {} still has another active session; skipping disconnect timer", username);
            return;
        }

        String roomId = findRoomIdByPlayer(username);
        if (roomId == null) {
            logger.debug("No room found for disconnected user {}. Nothing to schedule.", username);
            return;
        }

        // Marks the player as disconnected and marks when they should be removed from a game
        boolean gameActive = gameLifecycleService.gameExists(roomId);
        long disconnectDeadlineEpochMs = System.currentTimeMillis() + disconnectGracePeriodMs;
        if (gameActive && gameLifecycleService.playerExistsInGame(roomId, username)) {
            gameLifecycleService.markPlayerDisconnected(roomId, username, disconnectDeadlineEpochMs);
        }

        // Cancel any existing disconnect timer for this user
        PendingDisconnect existing = pendingDisconnects.remove(username);
        if (existing != null) {
            existing.future().cancel(false);
        }

        logger.info("WebSocket disconnected for user {}. Scheduling delayed cleanup ({} ms)",
                username, disconnectGracePeriodMs);

        // Schedule the user to be removed from the game if they don't reconnect in 2 minutes
        ScheduledFuture<?> future = taskScheduler.schedule(
                () -> cleanupDisconnectedUser(username, roomId),
                Instant.now().plusMillis(disconnectGracePeriodMs));

        pendingDisconnects.put(username, new PendingDisconnect(roomId, future));
    }

    /**
     * Cleans up a disconnected user from the game.
     * 
     * @param username The username of the disconnected user.
     * @param roomId   The room ID of the disconnected user.
     */
    private void cleanupDisconnectedUser(String username, String roomId) {
        pendingDisconnects.remove(username);

        if (hasActiveSession(username)) {
            logger.debug("Skipping disconnect cleanup for user {} because an active session exists", username);
            return;
        }

        logger.info("WebSocket grace period expired. Removing disconnected user: {}", username);

        Room room = roomService.getRoom(roomId);
        if (room == null || !room.hasPlayer(username)) {
            return;
        }

        try {
            logger.info("Automatically removing disconnected user '{}' from room '{}'", username,
                    room.getRoomName());
            boolean gameActive = gameLifecycleService.gameExists(roomId);
            roomService.leaveRoom(roomId, username, !gameActive);

            if (gameActive && gameLifecycleService.playerExistsInGame(roomId, username)) {
                gameLifecycleService.leaveGame(roomId, username);
            }
        } catch (Exception e) {
            logger.error("Failed to remove disconnected user '{}' from room '{}'", username,
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
     * @param username The username of the user.
     * @param sessionId The session ID of the user.
     */
    private void registerActiveSession(String username, String sessionId) {
        // Get the set of active sessions for the user, or create a new one if it
        // doesn't exist
        Set<String> sessions = activeSessionsByUser.computeIfAbsent(username, k -> ConcurrentHashMap.newKeySet());
        // Add the new session ID to the user's set of sessions
        sessions.add(sessionId);

        // Map the session ID back to the username for reverse lookup
        sessionToUser.put(sessionId, username);
    }

    /**
     * Unregisters an active WebSocket session for the given user.
     * 
     * @param username The username of the user.
     * @param sessionId The session ID of the user.
     */
    private void unregisterActiveSession(String username, String sessionId) {
        if (sessionId != null) {
            sessionToUser.remove(sessionId);
        }

        Set<String> sessions = activeSessionsByUser.getOrDefault(username, Collections.emptySet());
        if (sessionId != null) {
            sessions.remove(sessionId);
        }

        if (sessions.isEmpty()) {
            activeSessionsByUser.remove(username);
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