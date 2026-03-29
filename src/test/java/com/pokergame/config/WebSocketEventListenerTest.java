package com.pokergame.config;

import com.pokergame.model.Room;
import com.pokergame.service.GameLifecycleService;
import com.pokergame.service.RoomService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebSocketEventListenerTest {

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

    @Test
    void disconnectAndReconnectWithinGraceWindow_ShouldSkipPermanentRemoval() throws Exception {
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.initialize();
        WebSocketEventListener listener = new WebSocketEventListener(roomService, gameLifecycleService, scheduler, 120);

        Room room = new Room("room-1", "Room 1", "Alice", 6, 5, 10, 1000, null);
        room.addPlayer("Alice");

        when(roomService.getRooms()).thenReturn(List.of(room));
        when(gameLifecycleService.gameExists("room-1")).thenReturn(true);
        when(gameLifecycleService.playerExistsInGame("room-1", "Alice")).thenReturn(true);

        listener.handleWebSocketConnectListener(connectEvent("Alice", "session-a"));
        listener.handleWebSocketDisconnectListener(disconnectEvent("Alice", "session-a"));

        TimeUnit.MILLISECONDS.sleep(40);

        listener.handleWebSocketConnectListener(connectEvent("Alice", "session-b"));

        TimeUnit.MILLISECONDS.sleep(220);

        verify(gameLifecycleService).markPlayerDisconnected(eq("room-1"), eq("Alice"), anyLong());
        verify(gameLifecycleService).markPlayerReconnected("room-1", "Alice");
        verify(roomService, never()).leaveRoom(eq("room-1"), eq("Alice"), anyBoolean());
        verify(gameLifecycleService, never()).leaveGame("room-1", "Alice");
    }

    @Test
    void disconnectWithoutReconnect_AfterGraceWindow_ShouldPermanentlyRemovePlayer() throws Exception {
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.initialize();
        WebSocketEventListener listener = new WebSocketEventListener(roomService, gameLifecycleService, scheduler, 120);

        Room room = new Room("room-2", "Room 2", "Bob", 6, 5, 10, 1000, null);
        room.addPlayer("Bob");

        when(roomService.getRooms()).thenReturn(List.of(room));
        when(roomService.getRoom("room-2")).thenReturn(room);
        when(gameLifecycleService.gameExists("room-2")).thenReturn(true);
        when(gameLifecycleService.playerExistsInGame("room-2", "Bob")).thenReturn(true);

        listener.handleWebSocketConnectListener(connectEvent("Bob", "session-1"));
        listener.handleWebSocketDisconnectListener(disconnectEvent("Bob", "session-1"));

        TimeUnit.MILLISECONDS.sleep(240);

        verify(gameLifecycleService).markPlayerDisconnected(eq("room-2"), eq("Bob"), anyLong());
        verify(roomService).leaveRoom("room-2", "Bob", false);
        verify(gameLifecycleService).leaveGame("room-2", "Bob");
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
