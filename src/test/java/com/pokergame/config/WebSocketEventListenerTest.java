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
import java.util.List;
import java.util.concurrent.ScheduledFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import com.pokergame.util.RoomCoordinator;
import com.pokergame.util.LockLease;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class WebSocketEventListenerTest {
    private static final RoomTimingProperties ROOM_TIMINGS = new RoomTimingProperties(60_000L, 1_800_000L);

    @Test
    void reconnectAndRoomDestructionCompleteWithoutOppositeLockAcquisition() throws Exception {
        RoomService rooms = mock(RoomService.class);
        GameLifecycleService games = mock(GameLifecycleService.class);
        var scheduler = mock(TaskScheduler.class);
        var roomCoordinator = spy(new RoomCoordinator());
        var listener = new WebSocketEventListener(rooms, games, mock(RateLimitService.class), scheduler,
                roomCoordinator, ROOM_TIMINGS);
        PlayerPrincipal principal = new PlayerPrincipal("Alice", "room");
        listener.registerSession(principal, "original-session");
        var waiting = new CountDownLatch(1);
        var cleanupOwnsRoom = new CountDownLatch(1);
        AtomicReference<Thread> reconnectThread = new AtomicReference<>();
        doAnswer(invocation -> {
            if (Thread.currentThread() == reconnectThread.get()) {
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
                .when(games).cancelTimers("room", false);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Void> reconnect = executor.submit(() -> {
                reconnectThread.set(Thread.currentThread());
                listener.registerSession(principal, "replacement-session");
                return null;
            });
            assertTrue(waiting.await(2, TimeUnit.SECONDS));
            listener.publishRoomDestroyed("room");
            ArgumentCaptor<Runnable> scheduledCleanup = ArgumentCaptor.forClass(Runnable.class);
            verify(scheduler).schedule(scheduledCleanup.capture(), any(Instant.class));
            Future<Void> cleanup = executor.submit(() -> {
                scheduledCleanup.getValue().run();
                return null;
            });
            cleanup.get(3, TimeUnit.SECONDS);
            reconnect.get(3, TimeUnit.SECONDS);
        }
    }

    /** A retirement that runs late must not evict a player who has since re-joined. */
    @Test
    void lateMembershipRetirementDoesNotEvictRejoinedPlayer() {
        RoomService rooms = mock(RoomService.class);
        GameLifecycleService games = mock(GameLifecycleService.class);
        RateLimitService rateLimit = mock(RateLimitService.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        var listener = new WebSocketEventListener(rooms, games, rateLimit, scheduler,
                new RoomCoordinator(), ROOM_TIMINGS);
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

        assertTrue(room.hasPlayer("Bob"));
        assertEquals(secondMembership, room.getMembershipId("Bob"));
        verify(rateLimit, never()).cleanUpWs("Bob:room");

        // Disconnecting the retired session does not disturb Bob's active rejoined session.
        room.setPlayerConnected("Bob", true);
        listener.handleWebSocketDisconnectListener(event(new SessionDisconnectEvent(
                this, message(StompCommand.DISCONNECT, "bob-session-1",
                        new PlayerPrincipal("Bob", "room", firstMembership)),
                "bob-session-1", null)));
        assertTrue(room.isPlayerConnected("Bob"));
        verifyNoMoreInteractions(scheduler);

        // Disconnecting the rejoined session triggers normal disconnect grace scheduling.
        listener.handleWebSocketDisconnectListener(event(new SessionDisconnectEvent(
                this, message(StompCommand.DISCONNECT, "bob-session-2",
                        new PlayerPrincipal("Bob", "room", secondMembership)),
                "bob-session-2", null)));
        assertFalse(room.isPlayerConnected("Bob"));
        verify(scheduler, times(2)).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void connectReconcilesRecoveredDisconnectedPlayerEvenWithoutPendingRuntimeFuture() {
        RoomService roomService = mock(RoomService.class);
        GameLifecycleService lifecycle = mock(GameLifecycleService.class);
        RateLimitService rateLimit = mock(RateLimitService.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        WebSocketEventListener listener = new WebSocketEventListener(roomService, lifecycle, rateLimit, scheduler,
                new RoomCoordinator(), ROOM_TIMINGS);
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
                new RoomCoordinator(), ROOM_TIMINGS);
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
                new RoomCoordinator(), ROOM_TIMINGS);
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
                new RoomCoordinator(), ROOM_TIMINGS);
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
                new RoomCoordinator(), ROOM_TIMINGS);
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
                new RoomCoordinator(), ROOM_TIMINGS);
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
                new RoomCoordinator(), ROOM_TIMINGS);
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
                new RoomCoordinator(), ROOM_TIMINGS);
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
                new RoomCoordinator(), ROOM_TIMINGS);
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
                new RoomCoordinator(), ROOM_TIMINGS);
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
                new RoomCoordinator(), ROOM_TIMINGS);

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
                new RoomCoordinator(), ROOM_TIMINGS);
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
                new RoomCoordinator(), ROOM_TIMINGS);
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
                new RoomCoordinator(), ROOM_TIMINGS);

        Room room = new Room("room", "Room", "Alice", 4, 10, 20, 1000, null);
        String activeMemId = room.addPlayer("Alice");
        room.setPlayerConnected("Alice", true);
        when(roomService.getRoom("room")).thenReturn(room);

        PlayerPrincipal stalePrincipal = new PlayerPrincipal("Alice", "room", "stale-mem-id");

        // Stale connect is ignored (and disconnecting it has no effect)
        listener.handleWebSocketConnectListener(event(new SessionConnectEvent(this,
                message(StompCommand.CONNECT, "stale-session", stalePrincipal))));
        verify(lifecycle, never()).markPlayerReconnected("room", "Alice");
        listener.handleWebSocketDisconnectListener(event(new SessionDisconnectEvent(
                this, message(StompCommand.DISCONNECT, "stale-session", stalePrincipal), "stale-session", null)));
        assertTrue(room.isPlayerConnected("Alice"));
        verifyNoInteractions(scheduler);
        verify(rateLimit, never()).cleanUpWs(anyString());

        // Active session connects
        PlayerPrincipal activePrincipal = new PlayerPrincipal("Alice", "room", activeMemId);
        listener.handleWebSocketConnectListener(event(new SessionConnectEvent(this,
                message(StompCommand.CONNECT, "active-session", activePrincipal))));
        verify(lifecycle).markPlayerReconnected("room", "Alice");

        // Stale disconnect is ignored and does not mark Alice disconnected or schedule grace timer
        listener.handleWebSocketDisconnectListener(event(new SessionDisconnectEvent(this,
                message(StompCommand.DISCONNECT, "stale-session", stalePrincipal), "stale-session", null)));
        assertTrue(room.isPlayerConnected("Alice"));
        verifyNoInteractions(scheduler);
        verify(lifecycle, never()).markPlayerDisconnected(anyString(), anyString(), anyLong());

        // Active disconnect marks Alice disconnected and schedules grace timer
        listener.handleWebSocketDisconnectListener(event(new SessionDisconnectEvent(this,
                message(StompCommand.DISCONNECT, "active-session", activePrincipal), "active-session", null)));
        assertFalse(room.isPlayerConnected("Alice"));
        verify(scheduler).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void reconnectWinsLockDisconnectSkipsGraceTimerAndPreservesPresence() {
        RoomService roomService = mock(RoomService.class);
        GameLifecycleService lifecycle = mock(GameLifecycleService.class);
        RateLimitService rateLimit = mock(RateLimitService.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        WebSocketEventListener listener = new WebSocketEventListener(roomService, lifecycle,
                rateLimit, scheduler, new RoomCoordinator(), ROOM_TIMINGS);

        String roomId = "room-race-1";
        Room room = new Room(roomId, "Race Room", "Alice", 4, 10, 20, 1000, null);
        String membershipId = room.addPlayer("Alice");
        room.setPlayerConnected("Alice", true);
        when(roomService.getRoom(roomId)).thenReturn(room);

        PlayerPrincipal principal = new PlayerPrincipal("Alice", roomId, membershipId);
        listener.registerSession(principal, "session-1");
        listener.registerSession(principal, "session-2");
        verify(lifecycle, atLeastOnce()).markPlayerReconnected(roomId, "Alice");

        listener.handleWebSocketDisconnectListener(event(new SessionDisconnectEvent(
                this, message(StompCommand.DISCONNECT, "session-1", principal), "session-1", null)));

        verify(scheduler, never()).schedule(any(Runnable.class), any(Instant.class));
        assertThat(room.isPlayerConnected("Alice")).isTrue();
        verify(rateLimit, never()).cleanUpWs(anyString());

        // Second session stays registered until explicitly disconnected
        listener.handleWebSocketDisconnectListener(event(new SessionDisconnectEvent(
                this, message(StompCommand.DISCONNECT, "session-2", principal), "session-2", null)));
        assertThat(room.isPlayerConnected("Alice")).isFalse();
        verify(scheduler).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void disconnectWinsLockReconnectCancelsTimerAndPreventsEviction() {
        RoomService roomService = mock(RoomService.class);
        GameLifecycleService lifecycle = mock(GameLifecycleService.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        WebSocketEventListener listener = new WebSocketEventListener(roomService, lifecycle,
                mock(RateLimitService.class), scheduler, new RoomCoordinator(), ROOM_TIMINGS);

        String roomId = "room-race-2";
        Room room = new Room(roomId, "Race Room", "Alice", 4, 10, 20, 1000, null);
        String membershipId = room.addPlayer("Alice");
        room.setPlayerConnected("Alice", true);
        when(roomService.getRoom(roomId)).thenReturn(room);
        when(lifecycle.gameExists(roomId)).thenReturn(true);
        when(lifecycle.playerExistsInGame(roomId, "Alice")).thenReturn(true);

        PlayerPrincipal principal = new PlayerPrincipal("Alice", roomId, membershipId);
        listener.registerSession(principal, "session-1");

        ScheduledFuture<?> scheduledFuture = mock(ScheduledFuture.class);
        ArgumentCaptor<Runnable> cleanupCaptor = ArgumentCaptor.forClass(Runnable.class);
        doReturn(scheduledFuture).when(scheduler).schedule(cleanupCaptor.capture(), any(Instant.class));

        listener.handleWebSocketDisconnectListener(event(new SessionDisconnectEvent(
                this, message(StompCommand.DISCONNECT, "session-1", principal), "session-1", null)));

        assertThat(room.isPlayerConnected("Alice")).isFalse();
        Runnable graceCleanupCallback = cleanupCaptor.getValue();
        assertThat(graceCleanupCallback).isNotNull();

        listener.registerSession(principal, "session-2");

        verify(scheduledFuture).cancel(false);
        verify(lifecycle, atLeastOnce()).markPlayerReconnected(roomId, "Alice");

        graceCleanupCallback.run();

        verify(lifecycle, never()).removeDisconnectedPlayer(any(), any(), any(), anyLong());

        // session-2 stays registered after the stale grace callback executes
        room.setPlayerConnected("Alice", true);
        listener.handleWebSocketDisconnectListener(event(new SessionDisconnectEvent(
                this, message(StompCommand.DISCONNECT, "session-2", principal), "session-2", null)));
        assertThat(room.isPlayerConnected("Alice")).isFalse();
        verify(scheduler, times(2)).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void concurrentReconnectAndDisconnectPreservesPresenceWithoutDeadlock() throws Exception {
        RoomService roomService = mock(RoomService.class);
        GameLifecycleService lifecycle = mock(GameLifecycleService.class);
        RateLimitService rateLimit = mock(RateLimitService.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        WebSocketEventListener listener = new WebSocketEventListener(roomService, lifecycle,
                rateLimit, scheduler, new RoomCoordinator(), ROOM_TIMINGS);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            int iterations = 40;
            for (int i = 0; i < iterations; i++) {
                String roomId = "room-concurrent-" + i;
                Room room = new Room(roomId, "Concurrent Room", "Alice", 4, 10, 20, 1000, null);
                String membershipId = room.addPlayer("Alice");
                room.setPlayerConnected("Alice", true);
                when(roomService.getRoom(roomId)).thenReturn(room);

                ScheduledFuture<?> graceFuture = mock(ScheduledFuture.class);
                AtomicReference<Runnable> scheduledGrace = new AtomicReference<>();
                doAnswer(inv -> {
                    scheduledGrace.set(inv.getArgument(0));
                    return graceFuture;
                }).when(scheduler).schedule(any(Runnable.class), any(Instant.class));

                PlayerPrincipal principal = new PlayerPrincipal("Alice", roomId, membershipId);
                String origSession = "session-orig-" + i;
                String newSession = "session-new-" + i;
                listener.registerSession(principal, origSession);

                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<?> disconnectFuture = executor.submit(() -> {
                    barrier.await(2, TimeUnit.SECONDS);
                    listener.handleWebSocketDisconnectListener(event(new SessionDisconnectEvent(
                            this, message(StompCommand.DISCONNECT, origSession, principal), origSession, null)));
                    return null;
                });
                Future<?> connectFuture = executor.submit(() -> {
                    barrier.await(2, TimeUnit.SECONDS);
                    listener.registerSession(principal, newSession);
                    return null;
                });

                disconnectFuture.get(3, TimeUnit.SECONDS);
                connectFuture.get(3, TimeUnit.SECONDS);

                if (scheduledGrace.get() != null) {
                    verify(graceFuture).cancel(false);
                    scheduledGrace.get().run();
                    verify(lifecycle, never()).removeDisconnectedPlayer(eq(roomId), any(), any(), anyLong());
                }
                verify(rateLimit, never()).cleanUpWs("Alice:" + roomId);

                clearInvocations(scheduler);
                // Re-disconnecting origSession is a no-op; disconnecting newSession is active
                listener.handleWebSocketDisconnectListener(event(new SessionDisconnectEvent(
                        this, message(StompCommand.DISCONNECT, origSession, principal), origSession, null)));
                verifyNoInteractions(scheduler);

                listener.handleWebSocketDisconnectListener(event(new SessionDisconnectEvent(
                        this, message(StompCommand.DISCONNECT, newSession, principal), newSession, null)));
                verify(scheduler).schedule(any(Runnable.class), any(Instant.class));
            }
        }
    }

    @Test
    void roomDestructionCleansUpAllSessionsAndRegistrations() {
        RoomService roomService = mock(RoomService.class);
        GameLifecycleService lifecycle = mock(GameLifecycleService.class);
        RateLimitService rateLimit = mock(RateLimitService.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        WebSocketEventListener listener = new WebSocketEventListener(roomService, lifecycle,
                rateLimit, scheduler, new RoomCoordinator(), ROOM_TIMINGS);

        String roomId = "room-destroy-1";
        Room room = new Room(roomId, "Destroy Room", "Host", 4, 10, 20, 1000, null);
        String hostMem = room.addPlayer("Host");
        String aliceMem = room.addPlayer("Alice");
        String bobMem = room.addPlayer("Bob");
        when(roomService.getRoom(roomId)).thenReturn(room);

        PlayerPrincipal hostPrincipal = new PlayerPrincipal("Host", roomId, hostMem);
        PlayerPrincipal alicePrincipal = new PlayerPrincipal("Alice", roomId, aliceMem);
        PlayerPrincipal bobPrincipal = new PlayerPrincipal("Bob", roomId, bobMem);

        listener.registerSession(hostPrincipal, "host-s1");
        listener.registerSession(hostPrincipal, "host-s2");

        listener.registerSession(alicePrincipal, "alice-s1");
        ScheduledFuture<?> aliceFuture = mock(ScheduledFuture.class);
        ArgumentCaptor<Runnable> aliceGraceCaptor = ArgumentCaptor.forClass(Runnable.class);
        doReturn(aliceFuture).when(scheduler).schedule(aliceGraceCaptor.capture(), any(Instant.class));
        listener.handleWebSocketDisconnectListener(event(new SessionDisconnectEvent(
                this, message(StompCommand.DISCONNECT, "alice-s1", alicePrincipal), "alice-s1", null)));
        Runnable aliceGraceCallback = aliceGraceCaptor.getValue();

        listener.registerSession(bobPrincipal, "bob-s1");
        verify(scheduler, times(1)).schedule(any(Runnable.class), any(Instant.class));

        ArgumentCaptor<Runnable> destroyCaptor = ArgumentCaptor.forClass(Runnable.class);
        listener.publishRoomDestroyed(roomId);
        verify(scheduler, times(2)).schedule(destroyCaptor.capture(), any(Instant.class));
        destroyCaptor.getValue().run();

        verify(lifecycle).cancelTimers(roomId, false);
        verify(aliceFuture).cancel(false);
        verify(rateLimit).cleanUpWs("Host:" + roomId);
        verify(rateLimit).cleanUpWs("Alice:" + roomId);
        verify(rateLimit).cleanUpWs("Bob:" + roomId);

        aliceGraceCallback.run();
        verify(lifecycle, never()).removeDisconnectedPlayer(anyString(), anyString(), anyString(), anyLong());

        // Subsequent sessionId-only disconnects confirm sessionToPrincipal and active sessions were purged
        clearInvocations(scheduler, lifecycle);
        for (String sid : new String[] { "host-s1", "host-s2", "alice-s1", "bob-s1" }) {
            listener.handleWebSocketDisconnectListener(event(new SessionDisconnectEvent(
                    this, message(StompCommand.DISCONNECT, sid, null), sid, null)));
        }
        verifyNoInteractions(scheduler, lifecycle);
    }

    @Test
    void repeatedConnectionAndDestructionCyclesReleaseAllRegistrationsWithoutLeak() {
        RoomService roomService = mock(RoomService.class);
        GameLifecycleService lifecycle = mock(GameLifecycleService.class);
        RateLimitService rateLimit = mock(RateLimitService.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        WebSocketEventListener listener = new WebSocketEventListener(roomService, lifecycle,
                rateLimit, scheduler, new RoomCoordinator(), ROOM_TIMINGS);

        int cycles = 50;
        for (int i = 0; i < cycles; i++) {
            String roomId = "churn-room-" + i;
            Room room = new Room(roomId, "Churn Room", "Host", 4, 10, 20, 1000, null);
            String hostMem = room.addPlayer("Host");
            String guestMem = room.addPlayer("Guest");
            when(roomService.getRoom(roomId)).thenReturn(room);

            PlayerPrincipal host = new PlayerPrincipal("Host", roomId, hostMem);
            PlayerPrincipal guest = new PlayerPrincipal("Guest", roomId, guestMem);
            listener.registerSession(host, "host-sess-" + i);
            listener.registerSession(guest, "guest-sess-" + i);

            ScheduledFuture<?> future = mock(ScheduledFuture.class);
            doReturn(future).when(scheduler).schedule(any(Runnable.class), any(Instant.class));
            listener.handleWebSocketDisconnectListener(event(new SessionDisconnectEvent(
                    this, message(StompCommand.DISCONNECT, "guest-sess-" + i, guest), "guest-sess-" + i, null)));

            ArgumentCaptor<Runnable> cleanupCaptor = ArgumentCaptor.forClass(Runnable.class);
            listener.publishRoomDestroyed(roomId);
            verify(scheduler, times(2)).schedule(cleanupCaptor.capture(), any(Instant.class));
            cleanupCaptor.getValue().run();

            verify(future).cancel(false);
            verify(rateLimit).cleanUpWs("Host:" + roomId);
            verify(rateLimit).cleanUpWs("Guest:" + roomId);

            clearInvocations(scheduler, lifecycle);
            listener.handleWebSocketDisconnectListener(event(new SessionDisconnectEvent(
                    this, message(StompCommand.DISCONNECT, "host-sess-" + i, null), "host-sess-" + i, null)));
            listener.handleWebSocketDisconnectListener(event(new SessionDisconnectEvent(
                    this, message(StompCommand.DISCONNECT, "guest-sess-" + i, null), "guest-sess-" + i, null)));
            verifyNoInteractions(scheduler, lifecycle);
        }
    }

    @Test
    void roomCoordinatorReentrancyAndCrossRoomRejectionWithinSessionOperations() {
        RoomCoordinator roomCoordinator = new RoomCoordinator();
        RoomService roomService = mock(RoomService.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        WebSocketEventListener listener = new WebSocketEventListener(roomService,
                mock(GameLifecycleService.class), mock(RateLimitService.class), scheduler,
                roomCoordinator, ROOM_TIMINGS);

        String roomA = "room-iso-A";
        String roomB = "room-iso-B";
        PlayerPrincipal principalA = new PlayerPrincipal("Alice", roomA);
        PlayerPrincipal principalB = new PlayerPrincipal("Alice", roomB);

        try (LockLease _ = roomCoordinator.acquire(roomA)) {
            assertThat(roomCoordinator.isHeldByCurrentThread(roomA)).isTrue();
            assertThat(roomCoordinator.isHeldByCurrentThread(roomB)).isFalse();

            listener.registerSession(principalA, "session-a1");

            assertThatThrownBy(() -> listener.registerSession(principalB, "session-b1"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot coordinate room " + roomB);
        }

        try (LockLease _ = roomCoordinator.acquire(roomB)) {
            assertThat(roomCoordinator.isHeldByCurrentThread(roomB)).isTrue();
            listener.registerSession(principalB, "session-b1");
        }

        // Session mappings registered inside separate room leases drive subsequent disconnects properly
        listener.handleWebSocketDisconnectListener(event(new SessionDisconnectEvent(
                this, message(StompCommand.DISCONNECT, "session-a1", null), "session-a1", null)));
        listener.handleWebSocketDisconnectListener(event(new SessionDisconnectEvent(
                this, message(StompCommand.DISCONNECT, "session-b1", null), "session-b1", null)));
        verify(scheduler, times(2)).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void cancelRoomPendingDisconnectsRequiresRoomLockAndCancelsOnlyMatchingRoom() {
        RoomService roomService = mock(RoomService.class);
        GameLifecycleService lifecycle = mock(GameLifecycleService.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        RoomCoordinator roomCoordinator = new RoomCoordinator();
        WebSocketEventListener listener = new WebSocketEventListener(roomService, lifecycle,
                mock(RateLimitService.class), scheduler, roomCoordinator, ROOM_TIMINGS);

        String roomA = "room-pending-A";
        String roomB = "room-pending-B";
        ScheduledFuture<?> futureA = mock(ScheduledFuture.class);
        ScheduledFuture<?> futureB = mock(ScheduledFuture.class);
        ArgumentCaptor<Runnable> callbackCaptor = ArgumentCaptor.forClass(Runnable.class);
        doReturn(futureA).doReturn(futureB)
                .when(scheduler).schedule(callbackCaptor.capture(), any(Instant.class));

        listener.scheduleRecoveredDisconnect(roomA, "Alice", System.currentTimeMillis() + 30_000L);
        listener.scheduleRecoveredDisconnect(roomB, "Bob", System.currentTimeMillis() + 30_000L);
        List<Runnable> callbacks = callbackCaptor.getAllValues();

        assertThatThrownBy(() -> listener.cancelRoomPendingDisconnects(roomA))
                .isInstanceOf(IllegalStateException.class);

        try (LockLease _ = roomCoordinator.acquire(roomA)) {
            listener.cancelRoomPendingDisconnects(roomA);
        }

        verify(futureA).cancel(false);
        verify(futureB, never()).cancel(anyBoolean());

        callbacks.get(0).run();
        verify(lifecycle, never()).removeDisconnectedPlayer(eq(roomA), anyString(), any(), anyLong());

        Room bobRoom = new Room(roomB, "Room B", "Bob", 4, 10, 20, 1000, null);
        bobRoom.addPlayer("Bob");
        when(roomService.getRoom(roomB)).thenReturn(bobRoom);
        callbacks.get(1).run();
        verify(lifecycle).removeDisconnectedPlayer(eq(roomB), eq("Bob"), isNull(), anyLong());
    }
}
