package com.pokergame.config;

import com.pokergame.model.Room;
import com.pokergame.service.GameLifecycleService;
import com.pokergame.service.RoomService;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import com.pokergame.security.PlayerPrincipal;
import java.security.Principal;
import java.time.Duration;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
@DisplayName("WebSocket event listener")
class WebSocketEventListenerTest {

    private static final long DISCONNECT_GRACE_PERIOD_MS = 120L;

    @Mock
    private RoomService roomService;

    @Mock
    private GameLifecycleService gameLifecycleService;

    @Mock
    private com.pokergame.security.RateLimitService rateLimitService;

    private ThreadPoolTaskScheduler scheduler;

    @AfterEach
    void tearDown() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    @Nested
    @DisplayName("disconnect handling")
    class DisconnectHandling {

        @Test
        @DisplayName("should skip permanent removal when the player reconnects inside the grace period")
        void givenReconnectInsideGracePeriod_whenDisconnectHandled_thenPlayerIsNotRemoved() {
            WebSocketEventListener listener = createListener();
            Room room = createRoom("room-1", "Room 1", "Alice");

            lenient().when(roomService.getRooms()).thenReturn(List.of(room));
            lenient().when(gameLifecycleService.gameExists("room-1")).thenReturn(true);
            lenient().when(gameLifecycleService.playerExistsInGame("room-1", "Alice")).thenReturn(true);

            listener.handleWebSocketConnectListener(connectEvent("Alice", "room-1", "session-a"));
            listener.handleWebSocketDisconnectListener(disconnectEvent("Alice", "room-1", "session-a"));
            listener.handleWebSocketConnectListener(connectEvent("Alice", "room-1", "session-b"));

            Awaitility.await()
                    .atMost(Duration.ofSeconds(1))
                    .pollDelay(Duration.ofMillis(200))
                    .untilAsserted(() -> {
                        verify(gameLifecycleService).markPlayerDisconnected(eq("room-1"), eq("Alice"), anyLong());
                        verify(gameLifecycleService).markPlayerReconnected("room-1", "Alice");
                        verify(roomService, never()).leaveRoom(eq("room-1"), eq("Alice"), anyBoolean());
                        verify(gameLifecycleService, never()).leaveGame("room-1", "Alice");
                        verify(rateLimitService, never()).cleanUpWs(anyString());
                    });
        }

        @Test
        @DisplayName("should permanently remove the player when the grace period expires")
        void givenNoReconnect_whenGracePeriodExpires_thenPlayerIsRemoved() {
            WebSocketEventListener listener = createListener();
            Room room = createRoom("room-2", "Room 2", "Bob");

            lenient().when(roomService.getRooms()).thenReturn(List.of(room));
            lenient().when(roomService.getRoom("room-2")).thenReturn(room);
            lenient().when(gameLifecycleService.gameExists("room-2")).thenReturn(true);
            lenient().when(gameLifecycleService.playerExistsInGame("room-2", "Bob")).thenReturn(true);

            listener.handleWebSocketConnectListener(connectEvent("Bob", "room-2", "session-1"));
            listener.handleWebSocketDisconnectListener(disconnectEvent("Bob", "room-2", "session-1"));

            Awaitility.await()
                    .atMost(Duration.ofSeconds(1))
                    .untilAsserted(() -> {
                        verify(gameLifecycleService).markPlayerDisconnected(eq("room-2"), eq("Bob"), anyLong());
                        verify(rateLimitService).cleanUpWs("Bob:room-2");
                        verify(roomService).leaveRoom("room-2", "Bob", false);
                        verify(gameLifecycleService).leaveGame("room-2", "Bob");
                    });
        }

        @Test
        @DisplayName("should recover principal from session storage when event principal is null")
        void givenNullEventPrincipal_whenDisconnectHandled_thenPrincipalIsRecoveredAndHandled() {
            WebSocketEventListener listener = createListener();
            Room room = createRoom("room-3", "Room 3", "Charlie");

            lenient().when(roomService.getRooms()).thenReturn(List.of(room));
            lenient().when(roomService.getRoom("room-3")).thenReturn(room);
            lenient().when(gameLifecycleService.gameExists("room-3")).thenReturn(true);
            lenient().when(gameLifecycleService.playerExistsInGame("room-3", "Charlie")).thenReturn(true);

            // 1. Connect (this registers the session and principal)
            listener.handleWebSocketConnectListener(connectEvent("Charlie", "room-3", "session-c"));

            // 2. Disconnect with NULL principal in the event
            listener.handleWebSocketDisconnectListener(disconnectEvent(null, null, "session-c"));

            // 3. Verify it still correctly identified Charlie and started the cleanup flow
            Awaitility.await()
                    .atMost(Duration.ofSeconds(1))
                    .untilAsserted(() -> {
                        verify(gameLifecycleService).markPlayerDisconnected(eq("room-3"), eq("Charlie"), anyLong());
                        verify(rateLimitService).cleanUpWs("Charlie:room-3");
                        verify(roomService).leaveRoom("room-3", "Charlie", false);
                    });
        }

        @Test
        @DisplayName("should not start cleanup when one of multiple sessions for the same user disconnects")
        void givenMultiTabUser_whenOneSessionDisconnects_thenCleanupIsNotScheduled() {
            WebSocketEventListener listener = createListener();
            Room room = createRoom("room-4", "Room 4", "Dave");

            lenient().when(roomService.getRooms()).thenReturn(List.of(room));
            lenient().when(gameLifecycleService.gameExists("room-4")).thenReturn(true);
            lenient().when(gameLifecycleService.playerExistsInGame("room-4", "Dave")).thenReturn(true);

            // 1. Dave connects with two tabs (two sessions)
            listener.handleWebSocketConnectListener(connectEvent("Dave", "room-4", "session-d1"));
            listener.handleWebSocketConnectListener(connectEvent("Dave", "room-4", "session-d2"));

            // 2. Dave closes one tab
            listener.handleWebSocketDisconnectListener(disconnectEvent("Dave", "room-4", "session-d1"));

            // 3. Verify cleanup is NOT scheduled (no disconnect marking)
            Awaitility.await()
                    .atMost(Duration.ofSeconds(1))
                    .pollDelay(Duration.ofMillis(200))
                    .untilAsserted(() -> {
                        verify(gameLifecycleService, never()).markPlayerDisconnected(eq("room-4"), eq("Dave"), anyLong());
                        verify(roomService, never()).leaveRoom(eq("room-4"), eq("Dave"), anyBoolean());
                    });

            // 4. Dave closes the second tab
            listener.handleWebSocketDisconnectListener(disconnectEvent("Dave", "room-4", "session-d2"));

            // 5. Verify cleanup IS now scheduled
            Awaitility.await()
                    .atMost(Duration.ofSeconds(1))
                    .untilAsserted(() -> {
                        verify(gameLifecycleService).markPlayerDisconnected(eq("room-4"), eq("Dave"), anyLong());
                    });
        }
    }

    private WebSocketEventListener createListener() {
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.initialize();
        return new WebSocketEventListener(roomService, gameLifecycleService, rateLimitService, scheduler, DISCONNECT_GRACE_PERIOD_MS);
    }

    private Room createRoom(String roomId, String roomName, String playerName) {
        Room room = new Room(roomId, roomName, playerName, 6, 5, 10, 1000, null);
        room.addPlayer(playerName);
        return room;
    }

    private SessionConnectEvent connectEvent(String username, String roomId, String sessionId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionId(sessionId);
        if (username != null) {
            accessor.setUser(namedPrincipal(username, roomId));
        }
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        return new SessionConnectEvent(this, message);
    }

    private SessionDisconnectEvent disconnectEvent(String username, String roomId, String sessionId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.DISCONNECT);
        accessor.setSessionId(sessionId);
        Principal principal = username != null ? namedPrincipal(username, roomId) : null;
        if (principal != null) {
            accessor.setUser(principal);
        }
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        return new SessionDisconnectEvent(this, message, sessionId, null, principal);
    }

    private PlayerPrincipal namedPrincipal(String username, String roomId) {
        return new PlayerPrincipal(username, roomId);
    }
}
