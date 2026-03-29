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

import jakarta.annotation.PreDestroy;
import java.security.Principal;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Component
public class WebSocketEventListener {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketEventListener.class);

    private final RoomService roomService;
    private final GameLifecycleService gameLifecycleService;
    private final long disconnectGracePeriodMs;
    private final ConcurrentMap<String, Set<String>> activeSessionsByUser = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> sessionToUser = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, PendingDisconnect> pendingDisconnects = new ConcurrentHashMap<>();
    private final ScheduledExecutorService disconnectCleanupExecutor;

    @Autowired
    public WebSocketEventListener(RoomService roomService,
            GameLifecycleService gameLifecycleService,
            @Value("${poker.disconnect.grace-period-ms:120000}") long disconnectGracePeriodMs) {
        this(roomService, gameLifecycleService, disconnectGracePeriodMs, Executors.newSingleThreadScheduledExecutor());
    }

    WebSocketEventListener(RoomService roomService,
            GameLifecycleService gameLifecycleService,
            long disconnectGracePeriodMs,
            ScheduledExecutorService disconnectCleanupExecutor) {
        this.roomService = roomService;
        this.gameLifecycleService = gameLifecycleService;
        this.disconnectGracePeriodMs = disconnectGracePeriodMs;
        this.disconnectCleanupExecutor = disconnectCleanupExecutor;
    }

    @EventListener
    public void handleWebSocketConnectListener(SessionConnectEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        Principal user = headerAccessor.getUser();
        String sessionId = headerAccessor.getSessionId();

        if (user == null || sessionId == null) {
            return;
        }

        String username = user.getName();
        registerActiveSession(username, sessionId);
        logger.debug("Registered active WebSocket session {} for user {}", sessionId, username);

        PendingDisconnect pendingDisconnect = pendingDisconnects.remove(username);
        if (pendingDisconnect != null) {
            pendingDisconnect.future().cancel(false);

            if (gameLifecycleService.gameExists(pendingDisconnect.roomId())
                    && gameLifecycleService.playerExistsInGame(pendingDisconnect.roomId(), username)) {
                gameLifecycleService.markPlayerReconnected(pendingDisconnect.roomId(), username);
            }
        }
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
        if (hasActiveSession(username)) {
            logger.debug("User {} still has another active session; skipping disconnect timer", username);
            return;
        }

        String roomId = findRoomIdByPlayer(username);
        if (roomId == null) {
            logger.debug("No room found for disconnected user {}. Nothing to schedule.", username);
            return;
        }

        boolean gameActive = gameLifecycleService.gameExists(roomId);
        long disconnectDeadlineEpochMs = System.currentTimeMillis() + disconnectGracePeriodMs;
        if (gameActive && gameLifecycleService.playerExistsInGame(roomId, username)) {
            gameLifecycleService.markPlayerDisconnected(roomId, username, disconnectDeadlineEpochMs);
        }

        PendingDisconnect existing = pendingDisconnects.remove(username);
        if (existing != null) {
            existing.future().cancel(false);
        }

        logger.info("WebSocket disconnected for user {}. Scheduling delayed cleanup ({} ms)",
                username, disconnectGracePeriodMs);

        ScheduledFuture<?> future = disconnectCleanupExecutor.schedule(() -> cleanupDisconnectedUser(username, roomId),
                disconnectGracePeriodMs,
                TimeUnit.MILLISECONDS);

        pendingDisconnects.put(username, new PendingDisconnect(roomId, future));
    }

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

    private String findRoomIdByPlayer(String username) {
        for (Room room : roomService.getRooms()) {
            if (room.hasPlayer(username)) {
                return room.getRoomId();
            }
        }
        return null;
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
        pendingDisconnects.values().forEach(pending -> pending.future().cancel(false));
        disconnectCleanupExecutor.shutdownNow();
    }

    private record PendingDisconnect(String roomId, ScheduledFuture<?> future) {
    }
}