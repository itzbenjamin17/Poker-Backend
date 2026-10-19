package com.pokergame.config;

import com.pokergame.security.PlayerPrincipal;
import com.pokergame.security.RateLimitService;
import com.pokergame.service.GameLifecycleService;
import com.pokergame.service.RoomService;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.time.Instant;
import java.util.concurrent.ScheduledFuture;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class WebSocketEventListenerTest {
    @Test
    void connectReconcilesRecoveredDisconnectedPlayerEvenWithoutPendingRuntimeFuture() {
        RoomService roomService = mock(RoomService.class);
        GameLifecycleService lifecycle = mock(GameLifecycleService.class);
        RateLimitService rateLimit = mock(RateLimitService.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        WebSocketEventListener listener = new WebSocketEventListener(roomService, lifecycle, rateLimit, scheduler, 60_000);
        when(lifecycle.gameExists("room")).thenReturn(true);
        when(lifecycle.playerExistsInGame("room", "Alice")).thenReturn(true);

        listener.handleWebSocketConnectListener(event(new SessionConnectEvent(this,
                message(StompCommand.CONNECT, "session-a", new PlayerPrincipal("Alice", "room")))));

        verify(lifecycle).markPlayerReconnected("room", "Alice");
        verifyNoInteractions(scheduler);
    }

    @Test
    void obsoleteGraceCallbackCannotRemoveReconnectedPlayer() {
        RoomService roomService = mock(RoomService.class);
        GameLifecycleService lifecycle = mock(GameLifecycleService.class);
        RateLimitService rateLimit = mock(RateLimitService.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        WebSocketEventListener listener = new WebSocketEventListener(roomService, lifecycle, rateLimit, scheduler, 60_000);
        @SuppressWarnings("unchecked") ScheduledFuture<?> future = mock(ScheduledFuture.class);
        org.mockito.ArgumentCaptor<Runnable> callback = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        doReturn(future).when(scheduler).schedule(callback.capture(), any(Instant.class));
        PlayerPrincipal principal = new PlayerPrincipal("Alice", "room");

        listener.handleWebSocketDisconnectListener(event(new SessionDisconnectEvent(this,
                message(StompCommand.DISCONNECT, "session-a", principal), "session-a", null)));
        Runnable expired = callback.getValue();
        assertNotNull(expired);
        listener.handleWebSocketConnectListener(event(new SessionConnectEvent(this,
                message(StompCommand.CONNECT, "session-b", principal))));
        expired.run();

        verify(lifecycle, never()).removeDisconnectedPlayer(anyString(), anyString());
        verify(future).cancel(false);
    }

    @Test
    void connectFailureInMarkPlayerReconnectedLeavesPendingDisconnectActive() {
        RoomService roomService = mock(RoomService.class);
        GameLifecycleService lifecycle = mock(GameLifecycleService.class);
        RateLimitService rateLimit = mock(RateLimitService.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        WebSocketEventListener listener = new WebSocketEventListener(roomService, lifecycle, rateLimit, scheduler, 60_000);
        @SuppressWarnings("unchecked") ScheduledFuture<?> future = mock(ScheduledFuture.class);
        org.mockito.ArgumentCaptor<Runnable> callback = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        doReturn(future).when(scheduler).schedule(callback.capture(), any(Instant.class));
        PlayerPrincipal principal = new PlayerPrincipal("Alice", "room");

        // Disconnect player so a pending disconnect is registered
        listener.handleWebSocketDisconnectListener(event(new SessionDisconnectEvent(this,
                message(StompCommand.DISCONNECT, "session-a", principal), "session-a", null)));
        Runnable cleanupTask = callback.getValue();
        assertNotNull(cleanupTask);

        // Reconnect throws in markPlayerReconnected
        when(lifecycle.gameExists("room")).thenReturn(true);
        when(lifecycle.playerExistsInGame("room", "Alice")).thenReturn(true);
        doThrow(new RuntimeException("Simulated persistence failure")).when(lifecycle).markPlayerReconnected("room", "Alice");

        try {
            listener.handleWebSocketConnectListener(event(new SessionConnectEvent(this,
                    message(StompCommand.CONNECT, "session-b", principal))));
        } catch (RuntimeException ignored) {}

        // The future was NOT cancelled because markPlayerReconnected failed
        verify(future, never()).cancel(false);
    }

    @Test
    void disconnectSchedulesCleanupEvenIfMarkPlayerDisconnectedThrows() {
        RoomService roomService = mock(RoomService.class);
        GameLifecycleService lifecycle = mock(GameLifecycleService.class);
        RateLimitService rateLimit = mock(RateLimitService.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        WebSocketEventListener listener = new WebSocketEventListener(roomService, lifecycle, rateLimit, scheduler, 60_000);
        @SuppressWarnings("unchecked") ScheduledFuture<?> future = mock(ScheduledFuture.class);
        doReturn(future).when(scheduler).schedule(any(Runnable.class), any(Instant.class));
        PlayerPrincipal principal = new PlayerPrincipal("Alice", "room");

        when(lifecycle.gameExists("room")).thenReturn(true);
        when(lifecycle.playerExistsInGame("room", "Alice")).thenReturn(true);
        doThrow(new RuntimeException("Persistence unavailable")).when(lifecycle).markPlayerDisconnected(eq("room"), eq("Alice"), anyLong());

        listener.handleWebSocketDisconnectListener(event(new SessionDisconnectEvent(this,
                message(StompCommand.DISCONNECT, "session-a", principal), "session-a", null)));

        verify(scheduler).schedule(any(Runnable.class), any(Instant.class));
    }

    private static Message<byte[]> message(StompCommand command, String sessionId, PlayerPrincipal principal) {
        StompHeaderAccessor headers = StompHeaderAccessor.create(command);
        headers.setSessionId(sessionId);
        headers.setUser(principal);
        return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    }

    private static <T> T event(T value) { return value; }
}
