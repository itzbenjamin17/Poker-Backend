package com.pokergame.config;

import com.pokergame.model.Room;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import com.pokergame.service.RoomCoordinator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assertions;
import org.mockito.ArgumentCaptor;

class WebSocketEventListenerTest {
    @Test
    void reconnectAndRoomDestructionCompleteWithoutOppositeLockAcquisition() throws Exception {
        RoomService rooms = mock(RoomService.class);
        GameLifecycleService games = mock(GameLifecycleService.class);
        var scheduler = mock(TaskScheduler.class);
        var roomCoordinator = spy(new RoomCoordinator());
        var listener = new WebSocketEventListener(rooms, games, mock(RateLimitService.class), scheduler,
                roomCoordinator, 60_000);
        PlayerPrincipal principal = new PlayerPrincipal("Alice", "room");
        listener.registerSession(principal, "original-session");
        var waiting = new CountDownLatch(1);
        var cleanupOwnsRoom = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (Thread.currentThread().getName().equals("reconnect-regression")) {
                waiting.countDown();
                assertTrue(cleanupOwnsRoom.await(2, TimeUnit.SECONDS));
            }
            return invocation.callRealMethod();
        }).when(roomCoordinator).acquire("room");
        doAnswer(invocation -> {
            // Models same-room re-entry by the durable service proxy.
            try (var lock = roomCoordinator.acquire("room")) {
                assertNotNull(lock);
                return null;
            }
        }).when(games).markPlayerReconnected("room", "Alice");
        doAnswer(invocation -> { cleanupOwnsRoom.countDown(); return null; })
                .when(games).cancelRuntimeTasks("room", false);
        var reconnect = new FutureTask<Void>(() -> {
            listener.registerSession(principal, "replacement-session"); return null;
        });
        var reconnectThread = new Thread(reconnect, "reconnect-regression");
        reconnectThread.setDaemon(true);
        reconnectThread.start();
        assertTrue(waiting.await(2, TimeUnit.SECONDS));
        listener.publishRoomDestroyed("room");
        ArgumentCaptor<Runnable> scheduledCleanup = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).schedule(scheduledCleanup.capture(), any(Instant.class));
        var cleanup = new FutureTask<Void>(() -> { scheduledCleanup.getValue().run(); return null; });
        var cleanupThread = new Thread(cleanup, "cleanup-regression");
        cleanupThread.setDaemon(true);
        cleanupThread.start();
        cleanup.get(3, TimeUnit.SECONDS);
        reconnect.get(3, TimeUnit.SECONDS);
    }

    /** A retirement that runs late must not evict a player who has since re-joined. */
    @Test
    void lateMembershipRetirementDoesNotEvictRejoinedPlayer() {
        RoomService rooms = mock(RoomService.class);
        GameLifecycleService games = mock(GameLifecycleService.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        var listener = new WebSocketEventListener(rooms, games, mock(RateLimitService.class), scheduler,
                new RoomCoordinator(), 60_000);
        Room room = new Room("room", "Room", "Bob", 4, 10, 20, 500, null);
        String firstMembership = room.addPlayer("Bob");
        when(rooms.getRoom("room")).thenReturn(room);
        listener.registerSession(new PlayerPrincipal("Bob", "room", firstMembership), "bob-session-1");

        // The retirement is scheduled but has not run yet.
        listener.publishMembershipRetired("room", "Bob", firstMembership);
        ArgumentCaptor<Runnable> delayedRetirement = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).schedule(delayedRetirement.capture(), any(Instant.class));

        // Meanwhile Bob leaves and re-joins with a new membership and connects again.
        room.removePlayer("Bob");
        String secondMembership = room.addPlayer("Bob");
        assertNotEquals(firstMembership, secondMembership);
        listener.registerSession(new PlayerPrincipal("Bob", "room", secondMembership), "bob-session-2");

        delayedRetirement.getValue().run();

        assertTrue(listener.getActiveSessionCount("Bob:room") > 0);
        assertTrue(room.hasPlayer("Bob"));
        assertEquals(secondMembership, room.getMembershipId("Bob"));
    }

    @Test
    void connectReconcilesRecoveredDisconnectedPlayerEvenWithoutPendingRuntimeFuture() {
        RoomService roomService = mock(RoomService.class);
        GameLifecycleService lifecycle = mock(GameLifecycleService.class);
        RateLimitService rateLimit = mock(RateLimitService.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        WebSocketEventListener listener = new WebSocketEventListener(roomService, lifecycle, rateLimit, scheduler,
                new RoomCoordinator(), 60_000);
        when(lifecycle.gameExists("room")).thenReturn(true);
        when(lifecycle.playerExistsInGame("room", "Alice")).thenReturn(true);

        listener.handleWebSocketConnectListener(event(new SessionConnectEvent(this,
                message(StompCommand.CONNECT, "session-a", new PlayerPrincipal("Alice", "room")))));

        verify(lifecycle).markPlayerReconnected("room", "Alice");
        verifyNoInteractions(scheduler);
    }

    @Test
    void disconnectWithoutPrecedingRegisteredSessionIsIgnored() {
        RoomService roomService = mock(RoomService.class);
        GameLifecycleService lifecycle = mock(GameLifecycleService.class);
        RateLimitService rateLimit = mock(RateLimitService.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        WebSocketEventListener listener = new WebSocketEventListener(roomService, lifecycle, rateLimit, scheduler,
                new RoomCoordinator(), 60_000);
        PlayerPrincipal principal = new PlayerPrincipal("Alice", "room");

        listener.handleWebSocketDisconnectListener(event(new SessionDisconnectEvent(this,
                message(StompCommand.DISCONNECT, "unregistered-session", principal), "unregistered-session", null)));

        verifyNoInteractions(scheduler);
        verify(lifecycle, never()).markPlayerDisconnected(anyString(), anyString(), anyLong());
    }

    @Test
    void disconnectOneOfMultipleSessionsDoesNotScheduleGraceTimer() {
        RoomService roomService = mock(RoomService.class);
        GameLifecycleService lifecycle = mock(GameLifecycleService.class);
        RateLimitService rateLimit = mock(RateLimitService.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        WebSocketEventListener listener = new WebSocketEventListener(roomService, lifecycle, rateLimit, scheduler,
                new RoomCoordinator(), 60_000);
        PlayerPrincipal principal = new PlayerPrincipal("Alice", "room");

        // Connect session-1 and session-2
        listener.handleWebSocketConnectListener(event(new SessionConnectEvent(this,
                message(StompCommand.CONNECT, "session-1", principal))));
        listener.handleWebSocketConnectListener(event(new SessionConnectEvent(this,
                message(StompCommand.CONNECT, "session-2", principal))));

        // Disconnect session-1
        listener.handleWebSocketDisconnectListener(event(new SessionDisconnectEvent(this,
                message(StompCommand.DISCONNECT, "session-1", principal), "session-1", null)));

        verifyNoInteractions(scheduler);
        verify(lifecycle, never()).markPlayerDisconnected(anyString(), anyString(), anyLong());
    }

    @Test
    void disconnectLastSocketSchedulesGraceTimerWithExactInstant() {
        RoomService roomService = mock(RoomService.class);
        GameLifecycleService lifecycle = mock(GameLifecycleService.class);
        RateLimitService rateLimit = mock(RateLimitService.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        WebSocketEventListener listener = new WebSocketEventListener(roomService, lifecycle, rateLimit, scheduler,
                new RoomCoordinator(), 60_000);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        ArgumentCaptor<Instant> instantCaptor = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Long> deadlineCaptor = ArgumentCaptor.forClass(Long.class);
        doReturn(future).when(scheduler).schedule(any(Runnable.class), instantCaptor.capture());
        PlayerPrincipal principal = new PlayerPrincipal("Alice", "room");

        when(lifecycle.gameExists("room")).thenReturn(true);
        when(lifecycle.playerExistsInGame("room", "Alice")).thenReturn(true);

        // Connect then disconnect last socket
        listener.handleWebSocketConnectListener(event(new SessionConnectEvent(this,
                message(StompCommand.CONNECT, "session-1", principal))));
        listener.handleWebSocketDisconnectListener(event(new SessionDisconnectEvent(this,
                message(StompCommand.DISCONNECT, "session-1", principal), "session-1", null)));

        verify(lifecycle).markPlayerDisconnected(eq("room"), eq("Alice"), deadlineCaptor.capture());
        long deadline = deadlineCaptor.getValue();
        Instant scheduledInstant = instantCaptor.getValue();
        Assertions.assertEquals(Instant.ofEpochMilli(deadline), scheduledInstant,
                "TaskScheduler instant must exactly match the domain deadline epoch ms");
    }

    @Test
    void duplicateConnectIsIdempotent() {
        RoomService roomService = mock(RoomService.class);
        GameLifecycleService lifecycle = mock(GameLifecycleService.class);
        RateLimitService rateLimit = mock(RateLimitService.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        WebSocketEventListener listener = new WebSocketEventListener(roomService, lifecycle, rateLimit, scheduler,
                new RoomCoordinator(), 60_000);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        doReturn(future).when(scheduler).schedule(any(Runnable.class), any(Instant.class));
        PlayerPrincipal principal = new PlayerPrincipal("Alice", "room");

        when(lifecycle.gameExists("room")).thenReturn(true);
        when(lifecycle.playerExistsInGame("room", "Alice")).thenReturn(true);

        // Connect session-1 twice
        listener.handleWebSocketConnectListener(event(new SessionConnectEvent(this,
                message(StompCommand.CONNECT, "session-1", principal))));
        listener.handleWebSocketConnectListener(event(new SessionConnectEvent(this,
                message(StompCommand.CONNECT, "session-1", principal))));

        // Disconnecting session-1 once transitions to 0 and schedules
        listener.handleWebSocketDisconnectListener(event(new SessionDisconnectEvent(this,
                message(StompCommand.DISCONNECT, "session-1", principal), "session-1", null)));

        verify(scheduler, times(1)).schedule(any(Runnable.class), any(Instant.class));

        // A second disconnect for session-1 is a duplicate/already-retired disconnect and produces no new schedule
        listener.handleWebSocketDisconnectListener(event(new SessionDisconnectEvent(this,
                message(StompCommand.DISCONNECT, "session-1", principal), "session-1", null)));

        verify(scheduler, times(1)).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void disconnectWithSessionOwnershipMismatchIsIgnored() {
        RoomService roomService = mock(RoomService.class);
        GameLifecycleService lifecycle = mock(GameLifecycleService.class);
        RateLimitService rateLimit = mock(RateLimitService.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        WebSocketEventListener listener = new WebSocketEventListener(roomService, lifecycle, rateLimit, scheduler,
                new RoomCoordinator(), 60_000);
        PlayerPrincipal alicePrincipal = new PlayerPrincipal("Alice", "room");
        PlayerPrincipal bobPrincipal = new PlayerPrincipal("Bob", "room");

        // Alice connects session-1
        listener.handleWebSocketConnectListener(event(new SessionConnectEvent(this,
                message(StompCommand.CONNECT, "session-1", alicePrincipal))));

        // Disconnect arrives for session-1 with Bob's principal
        listener.handleWebSocketDisconnectListener(event(new SessionDisconnectEvent(this,
                message(StompCommand.DISCONNECT, "session-1", bobPrincipal), "session-1", null)));

        // Must be ignored - no scheduling, Alice still connected
        verifyNoInteractions(scheduler);
        verify(lifecycle, never()).markPlayerDisconnected(anyString(), anyString(), anyLong());
    }

    @Test
    void obsoleteGraceCallbackCannotRemoveReconnectedPlayer() {
        RoomService roomService = mock(RoomService.class);
        GameLifecycleService lifecycle = mock(GameLifecycleService.class);
        RateLimitService rateLimit = mock(RateLimitService.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        WebSocketEventListener listener = new WebSocketEventListener(roomService, lifecycle, rateLimit, scheduler,
                new RoomCoordinator(), 60_000);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        ArgumentCaptor<Runnable> callback = ArgumentCaptor.forClass(Runnable.class);
        doReturn(future).when(scheduler).schedule(callback.capture(), any(Instant.class));
        PlayerPrincipal principal = new PlayerPrincipal("Alice", "room");

        listener.handleWebSocketConnectListener(event(new SessionConnectEvent(this,
                message(StompCommand.CONNECT, "session-a", principal))));
        listener.handleWebSocketDisconnectListener(event(new SessionDisconnectEvent(this,
                message(StompCommand.DISCONNECT, "session-a", principal), "session-a", null)));
        Runnable expired = callback.getValue();
        assertNotNull(expired);
        listener.handleWebSocketConnectListener(event(new SessionConnectEvent(this,
                message(StompCommand.CONNECT, "session-b", principal))));
        expired.run();

        verify(lifecycle, never()).removeDisconnectedPlayer(anyString(), anyString(), anyString(), anyLong());
        verify(future).cancel(false);
    }

    @Test
    void connectFailureInMarkPlayerReconnectedLeavesPendingDisconnectActive() {
        RoomService roomService = mock(RoomService.class);
        GameLifecycleService lifecycle = mock(GameLifecycleService.class);
        RateLimitService rateLimit = mock(RateLimitService.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        WebSocketEventListener listener = new WebSocketEventListener(roomService, lifecycle, rateLimit, scheduler,
                new RoomCoordinator(), 60_000);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        ArgumentCaptor<Runnable> callback = ArgumentCaptor.forClass(Runnable.class);
        doReturn(future).when(scheduler).schedule(callback.capture(), any(Instant.class));
        PlayerPrincipal principal = new PlayerPrincipal("Alice", "room");

        // Connect player first, then disconnect so a pending disconnect is registered
        listener.handleWebSocketConnectListener(event(new SessionConnectEvent(this,
                message(StompCommand.CONNECT, "session-a", principal))));
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
        WebSocketEventListener listener = new WebSocketEventListener(roomService, lifecycle, rateLimit, scheduler,
                new RoomCoordinator(), 60_000);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        doReturn(future).when(scheduler).schedule(any(Runnable.class), any(Instant.class));
        PlayerPrincipal principal = new PlayerPrincipal("Alice", "room");

        // Connect first so session is known
        listener.handleWebSocketConnectListener(event(new SessionConnectEvent(this,
                message(StompCommand.CONNECT, "session-a", principal))));

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

    @Test
    void registerInitialConnection_SchedulesGraceTimerWithAcceptanceDeadline() {
        RoomService roomService = mock(RoomService.class);
        GameLifecycleService lifecycle = mock(GameLifecycleService.class);
        RateLimitService rateLimit = mock(RateLimitService.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        WebSocketEventListener listener = new WebSocketEventListener(roomService, lifecycle, rateLimit, scheduler,
                new RoomCoordinator(), 60_000);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        ArgumentCaptor<Instant> instantCaptor = ArgumentCaptor.forClass(Instant.class);
        doReturn(future).when(scheduler).schedule(any(Runnable.class), instantCaptor.capture());

        Room room = new Room("room", "Room", "Alice", 4, 10, 20, 1000, null);
        room.addPlayer("Alice");
        String membershipId = room.getMembershipId("Alice");
        when(roomService.getRoom("room")).thenReturn(room);

        long deadline = System.currentTimeMillis() + 60_000;
        listener.registerInitialConnection("room", "Alice", membershipId, deadline);

        verify(scheduler).schedule(any(Runnable.class), eq(Instant.ofEpochMilli(deadline)));
    }

    @Test
    void registerInitialConnection_PlayerAlreadyConnected_DoesNotScheduleTimer() {
        RoomService roomService = mock(RoomService.class);
        GameLifecycleService lifecycle = mock(GameLifecycleService.class);
        RateLimitService rateLimit = mock(RateLimitService.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        WebSocketEventListener listener = new WebSocketEventListener(roomService, lifecycle, rateLimit, scheduler,
                new RoomCoordinator(), 60_000);

        Room room = new Room("room", "Room", "Alice", 4, 10, 20, 1000, null);
        room.addPlayer("Alice");
        String membershipId = room.getMembershipId("Alice");
        when(roomService.getRoom("room")).thenReturn(room);

        PlayerPrincipal principal = new PlayerPrincipal("Alice", "room");
        // Alice connects first
        listener.handleWebSocketConnectListener(event(new SessionConnectEvent(this,
                message(StompCommand.CONNECT, "session-1", principal))));

        long deadline = System.currentTimeMillis() + 60_000;
        // Late registration runs
        listener.registerInitialConnection("room", "Alice", membershipId, deadline);

        verifyNoInteractions(scheduler);
    }

    @Test
    void disconnectSetsRoomPlayerConnectedFalseAndAbsenceDeadlineUnderRoomLock() {
        RoomService roomService = mock(RoomService.class);
        GameLifecycleService lifecycle = mock(GameLifecycleService.class);
        RateLimitService rateLimit = mock(RateLimitService.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        WebSocketEventListener listener = new WebSocketEventListener(roomService, lifecycle, rateLimit, scheduler,
                new RoomCoordinator(), 60_000);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        ArgumentCaptor<Instant> instantCaptor = ArgumentCaptor.forClass(Instant.class);
        doReturn(future).when(scheduler).schedule(any(Runnable.class), instantCaptor.capture());

        Room room = new Room("room", "Room", "Alice", 4, 10, 20, 1000, null);
        room.addPlayer("Alice");
        room.setPlayerConnected("Alice", true);
        when(roomService.getRoom("room")).thenReturn(room);

        PlayerPrincipal principal = new PlayerPrincipal("Alice", "room");

        // Alice connects
        listener.handleWebSocketConnectListener(event(new SessionConnectEvent(this,
                message(StompCommand.CONNECT, "session-1", principal))));
        assertTrue(room.isPlayerConnected("Alice"));

        // Alice disconnects
        listener.handleWebSocketDisconnectListener(event(new SessionDisconnectEvent(this,
                message(StompCommand.DISCONNECT, "session-1", principal), "session-1", null)));

        // Verify room connection state and absence deadline updated under room monitor
        assertFalse(room.isPlayerConnected("Alice"), "Player connection flag in room aggregate must be cleared on disconnect");
        assertNotNull(room.getAbsenceDeadline("Alice"), "Room absence deadline must be recorded on disconnect");
        assertEquals(instantCaptor.getValue().toEpochMilli(), room.getAbsenceDeadline("Alice"),
                "Room absence deadline must match the scheduled grace expiry instant");
    }

    @Test
    void cleanupDisconnectedUser_WhenPlayerNotInRoomNorGame_SkipsRemoval() {
        RoomService roomService = mock(RoomService.class);
        GameLifecycleService lifecycle = mock(GameLifecycleService.class);
        RateLimitService rateLimit = mock(RateLimitService.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        WebSocketEventListener listener = new WebSocketEventListener(roomService, lifecycle, rateLimit, scheduler,
                new RoomCoordinator(), 60_000);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        ArgumentCaptor<Runnable> callback = ArgumentCaptor.forClass(Runnable.class);
        doReturn(future).when(scheduler).schedule(callback.capture(), any(Instant.class));

        PlayerPrincipal principal = new PlayerPrincipal("Alice", "room");
        listener.handleWebSocketConnectListener(event(new SessionConnectEvent(this,
                message(StompCommand.CONNECT, "session-1", principal))));
        listener.handleWebSocketDisconnectListener(event(new SessionDisconnectEvent(this,
                message(StompCommand.DISCONNECT, "session-1", principal), "session-1", null)));

        Runnable cleanup = callback.getValue();
        assertNotNull(cleanup);

        // Neither in room nor in game when timer fires
        when(roomService.getRoom("room")).thenReturn(null);
        when(lifecycle.gameExists("room")).thenReturn(false);

        cleanup.run();

        verify(lifecycle, never()).removeDisconnectedPlayer(anyString(), anyString(), any(), anyLong());
    }

    @Test
    void staleMembershipConnectAndDisconnectAreIgnoredWithoutPoisoningActivePlayer() {
        RoomService roomService = mock(RoomService.class);
        GameLifecycleService lifecycle = mock(GameLifecycleService.class);
        RateLimitService rateLimit = mock(RateLimitService.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        WebSocketEventListener listener = new WebSocketEventListener(roomService, lifecycle, rateLimit, scheduler,
                new RoomCoordinator(), 60_000);

        Room room = new Room("room", "Room", "Alice", 4, 10, 20, 1000, null);
        String activeMemId = room.addPlayer("Alice");
        room.setPlayerConnected("Alice", true);
        when(roomService.getRoom("room")).thenReturn(room);

        PlayerPrincipal stalePrincipal = new PlayerPrincipal("Alice", "room", "stale-mem-id");

        // Stale connect is ignored
        listener.handleWebSocketConnectListener(event(new SessionConnectEvent(this,
                message(StompCommand.CONNECT, "stale-session", stalePrincipal))));
        verify(lifecycle, never()).markPlayerReconnected("room", "Alice");
        assertEquals(0, listener.getActiveSessionCount("Alice:room"));

        // Active session connects
        PlayerPrincipal activePrincipal = new PlayerPrincipal("Alice", "room", activeMemId);
        listener.handleWebSocketConnectListener(event(new SessionConnectEvent(this,
                message(StompCommand.CONNECT, "active-session", activePrincipal))));
        assertEquals(1, listener.getActiveSessionCount("Alice:room"));

        // Stale disconnect is ignored and does not mark Alice disconnected or schedule grace timer
        listener.handleWebSocketDisconnectListener(event(new SessionDisconnectEvent(this,
                message(StompCommand.DISCONNECT, "stale-session", stalePrincipal), "stale-session", null)));
        assertTrue(room.isPlayerConnected("Alice"));
        verifyNoInteractions(scheduler);
        verify(lifecycle, never()).markPlayerDisconnected(anyString(), anyString(), anyLong());
    }
}
