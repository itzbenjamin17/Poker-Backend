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

import java.security.Principal;
import java.time.Duration;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
@DisplayName("WebSocket event listener")
class WebSocketEventListenerTest {

    private static final long DISCONNECT_GRACE_PERIOD_MS = 120L;

    @Mock
    private RoomService roomService;

    @Mock
    private GameLifecycleService gameLifecycleService;

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

            when(roomService.getRooms()).thenReturn(List.of(room));
            when(gameLifecycleService.gameExists("room-1")).thenReturn(true);
            when(gameLifecycleService.playerExistsInGame("room-1", "Alice")).thenReturn(true);

            listener.handleWebSocketConnectListener(connectEvent("Alice", "session-a"));
            listener.handleWebSocketDisconnectListener(disconnectEvent("Alice", "session-a"));
            listener.handleWebSocketConnectListener(connectEvent("Alice", "session-b"));

            Awaitility.await()
                    .atMost(Duration.ofSeconds(1))
                    .pollDelay(Duration.ofMillis(200))
                    .untilAsserted(() -> {
                        verify(gameLifecycleService).markPlayerDisconnected(eq("room-1"), eq("Alice"), anyLong());
                        verify(gameLifecycleService).markPlayerReconnected("room-1", "Alice");
                        verify(roomService, never()).leaveRoom(eq("room-1"), eq("Alice"), anyBoolean());
                        verify(gameLifecycleService, never()).leaveGame("room-1", "Alice");
                    });
        }

        @Test
        @DisplayName("should permanently remove the player when the grace period expires")
        void givenNoReconnect_whenGracePeriodExpires_thenPlayerIsRemoved() {
            WebSocketEventListener listener = createListener();
            Room room = createRoom("room-2", "Room 2", "Bob");

            when(roomService.getRooms()).thenReturn(List.of(room));
            when(roomService.getRoom("room-2")).thenReturn(room);
            when(gameLifecycleService.gameExists("room-2")).thenReturn(true);
            when(gameLifecycleService.playerExistsInGame("room-2", "Bob")).thenReturn(true);

            listener.handleWebSocketConnectListener(connectEvent("Bob", "session-1"));
            listener.handleWebSocketDisconnectListener(disconnectEvent("Bob", "session-1"));

            Awaitility.await()
                    .atMost(Duration.ofSeconds(1))
                    .untilAsserted(() -> {
                        verify(gameLifecycleService).markPlayerDisconnected(eq("room-2"), eq("Bob"), anyLong());
                        verify(roomService).leaveRoom("room-2", "Bob", false);
                        verify(gameLifecycleService).leaveGame("room-2", "Bob");
                    });
        }
    }

    private WebSocketEventListener createListener() {
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.initialize();
        return new WebSocketEventListener(roomService, gameLifecycleService, scheduler, DISCONNECT_GRACE_PERIOD_MS);
    }

    private Room createRoom(String roomId, String roomName, String playerName) {
        Room room = new Room(roomId, roomName, playerName, 6, 5, 10, 1000, null);
        room.addPlayer(playerName);
        return room;
    }

    private SessionConnectEvent connectEvent(String username, String sessionId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionId(sessionId);
        accessor.setUser(namedPrincipal(username));
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        return new SessionConnectEvent(this, message);
    }

    private SessionDisconnectEvent disconnectEvent(String username, String sessionId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.DISCONNECT);
        accessor.setSessionId(sessionId);
        accessor.setUser(namedPrincipal(username));
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        return new SessionDisconnectEvent(this, message, sessionId, null, namedPrincipal(username));
    }

    private Principal namedPrincipal(String username) {
        return () -> username;
    }
}
