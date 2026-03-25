package com.pokergame.config;

import com.pokergame.model.Room;
import com.pokergame.service.GameLifecycleService;
import com.pokergame.service.RoomService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import jakarta.annotation.PreDestroy;
import java.security.Principal;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public class WebSocketEventListener {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketEventListener.class);
    private static final long DISCONNECT_GRACE_PERIOD_MS = 6000;

    private final RoomService roomService;
    private final GameLifecycleService gameLifecycleService;
    private final ConcurrentMap<String, Set<String>> activeSessionsByUser = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> sessionToUser = new ConcurrentHashMap<>();
    private final ScheduledExecutorService disconnectCleanupExecutor = Executors.newSingleThreadScheduledExecutor();

    public WebSocketEventListener(RoomService roomService, GameLifecycleService gameLifecycleService) {
        this.roomService = roomService;
        this.gameLifecycleService = gameLifecycleService;
    }

    @EventListener
    public void handleWebSocketConnectListener(SessionConnectEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        Principal user = headerAccessor.getUser();
        String sessionId = headerAccessor.getSessionId();

        if (user == null || sessionId == null) {
            return;
        }

        registerActiveSession(user.getName(), sessionId);
        logger.debug("Registered active WebSocket session {} for user {}", sessionId, user.getName());
    }

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

        unregisterActiveSession(username, sessionId);
        logger.info("WebSocket disconnected for user {}. Scheduling delayed cleanup ({} ms)",
                username, DISCONNECT_GRACE_PERIOD_MS);

        disconnectCleanupExecutor.schedule(() -> cleanupDisconnectedUser(username),
                DISCONNECT_GRACE_PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    private void cleanupDisconnectedUser(String username) {
        if (hasActiveSession(username)) {
            logger.debug("Skipping disconnect cleanup for user {} because an active session exists", username);
            return;
        }

        logger.info("WebSocket grace period expired. Removing disconnected user: {}", username);

        // Find which room the player is in and remove them
        for (Room room : roomService.getRooms()) {
            if (!room.hasPlayer(username)) {
                continue;
            }

            try {
                logger.info("Automatically removing disconnected user '{}' from room '{}'", username,
                        room.getRoomName());
                String roomId = room.getRoomId();
                roomService.leaveRoom(roomId, username);

                if (gameLifecycleService.gameExists(roomId)) {
                    gameLifecycleService.leaveGame(roomId, username);
                }
            } catch (Exception e) {
                logger.error("Failed to remove disconnected user '{}' from room '{}'", username,
                        room.getRoomName(), e);
            }

            // Player should only be in one room at a time.
            break;
        }
    }

    private void registerActiveSession(String username, String sessionId) {
        activeSessionsByUser.computeIfAbsent(username, ignored -> ConcurrentHashMap.newKeySet()).add(sessionId);
        sessionToUser.put(sessionId, username);
    }

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

    private boolean hasActiveSession(String username) {
        Set<String> sessions = activeSessionsByUser.get(username);
        return sessions != null && !sessions.isEmpty();
    }

    @PreDestroy
    public void shutdownExecutor() {
        disconnectCleanupExecutor.shutdownNow();
    }
}