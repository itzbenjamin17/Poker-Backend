package com.pokergame.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pokergame.model.Room;
import com.pokergame.security.PlayerPrincipal;
import com.pokergame.security.RateLimitService;
import com.pokergame.service.GameLifecycleService;
import com.pokergame.service.RoomService;
import com.pokergame.service.event.RoomDestroyed;
import com.pokergame.service.event.RoomMemberRegistered;
import com.pokergame.service.event.RoomMembershipEnded;
import com.pokergame.testutil.DeterministicTaskScheduler;
import com.pokergame.testutil.DeterministicTaskScheduler.ScheduledTask;
import com.pokergame.testutil.MutableClock;
import com.pokergame.testutil.TestRoomLocks;
import com.pokergame.testutil.TestTimings;
import com.pokergame.util.LockLease;
import com.pokergame.util.RoomCoordinator;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

/** Unit tests for {@link WebSocketEventListener}. */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class WebSocketEventListenerTest {

    private static final long INITIAL_EPOCH_MS = 1_700_000_000_000L;
    private static final long GRACE_PERIOD_MS = 60_000L;
    private static final RoomTimingProperties ROOM_TIMINGS = TestTimings.room(GRACE_PERIOD_MS);

    @Mock
    private RoomService roomService;

    @Mock
    private GameLifecycleService lifecycle;

    @Mock
    private RateLimitService rateLimit;

    private MutableClock clock;
    private DeterministicTaskScheduler scheduler;
    private RoomCoordinator roomCoordinator;
    private WebSocketEventListener listener;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(INITIAL_EPOCH_MS);
        scheduler = new DeterministicTaskScheduler(clock);
        roomCoordinator = new RoomCoordinator();
        listener = new WebSocketEventListener(
                roomService, lifecycle, rateLimit, scheduler, roomCoordinator, ROOM_TIMINGS, clock);
    }

    @Test
    void reconnectAndRoomDestructionCompleteWithoutOppositeLockAcquisition() throws Exception {
        PlayerPrincipal principal = new PlayerPrincipal("Alice", "room");
        listener.registerSession(principal, "original-session");

        CountDownLatch cleanupInsideLock = new CountDownLatch(1);
        CountDownLatch releaseCleanup = new CountDownLatch(1);

        doAnswer(invocation -> {
            try (LockLease _ = roomCoordinator.acquire("room")) {
                assertThat(roomCoordinator.isHeldByCurrentThread("room")).isTrue();
                return null;
            }
        }).when(lifecycle).markPlayerReconnected("room", "Alice");

        doAnswer(invocation -> {
            cleanupInsideLock.countDown();
            assertThat(releaseCleanup.await(2, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(lifecycle).cancelTimers("room", false);

        listener.publishRoomDestroyed("room");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Void> cleanup = executor.submit(() -> {
                scheduler.runAllPending();
                return null;
            });
            assertThat(cleanupInsideLock.await(2, TimeUnit.SECONDS)).isTrue();

            Future<Void> reconnect = executor.submit(() -> {
                listener.registerSession(principal, "replacement-session");
                return null;
            });

            Awaitility.await()
                    .atMost(Duration.ofSeconds(2))
                    .until(() -> roomCoordinator.lockReservationCount("room") >= 2);
            releaseCleanup.countDown();

            cleanup.get(3, TimeUnit.SECONDS);
            reconnect.get(3, TimeUnit.SECONDS);
        } finally {
            releaseCleanup.countDown();
            executor.shutdownNow();
            executor.awaitTermination(2, TimeUnit.SECONDS);
        }

        verify(lifecycle).cancelTimers("room", false);
        verify(rateLimit).cleanUpWs("Alice:room");

        listener.handleWebSocketDisconnectListener(disconnectEvent("replacement-session", principal));
        assertThat(scheduler.pendingCount()).isEqualTo(1);
    }

    @Test
    void lateMembershipEndedDoesNotEvictRejoinedPlayer() {
        Room room = new Room("room", "Room", "Bob", 4, 10, 20, 500, null, clock);
        String firstMembership = room.addPlayer("Bob");
        when(roomService.getRoom("room")).thenReturn(room);
        listener.registerSession(new PlayerPrincipal("Bob", "room", firstMembership), "bob-session-1");

        listener.publishMembershipEnded("room", "Bob", firstMembership);
        assertThat(scheduler.pendingCount()).isEqualTo(1);

        String secondMembership = TestRoomLocks.withRoomLock(roomCoordinator, "room", () -> {
            room.removePlayer("Bob");
            String membership = room.addPlayer("Bob");
            room.setPlayerConnected("Bob", true);
            return membership;
        });
        assertThat(secondMembership).isNotEqualTo(firstMembership);
        listener.registerSession(new PlayerPrincipal("Bob", "room", secondMembership), "bob-session-2");

        assertThat(scheduler.runAllPending()).isEqualTo(1);

        TestRoomLocks.runWithRoomLock(roomCoordinator, "room", () -> {
            assertThat(room.hasPlayer("Bob")).isTrue();
            assertThat(room.getMembershipId("Bob")).isEqualTo(secondMembership);
        });
        verify(rateLimit, never()).cleanUpWs("Bob:room");

        listener.handleWebSocketDisconnectListener(
                disconnectEvent("bob-session-1", new PlayerPrincipal("Bob", "room", firstMembership)));
        assertThat(room.isPlayerConnected("Bob")).isTrue();
        assertThat(scheduler.pendingCount()).isZero();

        listener.handleWebSocketDisconnectListener(
                disconnectEvent("bob-session-2", new PlayerPrincipal("Bob", "room", secondMembership)));
        assertThat(room.isPlayerConnected("Bob")).isFalse();
        assertThat(scheduler.pendingCount()).isEqualTo(1);
    }

    @Test
    void connectReconcilesRecoveredDisconnectedPlayerEvenWithoutPendingRuntimeFuture() {
        listener.handleWebSocketConnectListener(connectEvent("session-a", new PlayerPrincipal("Alice", "room")));

        verify(lifecycle).markPlayerReconnected("room", "Alice");
        assertThat(scheduler.scheduledTasks()).isEmpty();
    }

    @Test
    void disconnectWithoutPrecedingRegisteredSessionIsIgnored() {
        PlayerPrincipal principal = new PlayerPrincipal("Alice", "room");

        listener.handleWebSocketDisconnectListener(disconnectEvent("unregistered-session", principal));

        assertThat(scheduler.scheduledTasks()).isEmpty();
        verify(lifecycle, never()).markPlayerDisconnected(anyString(), anyString(), anyLong());
    }

    @Test
    void disconnectOneOfMultipleSessionsDoesNotScheduleGraceTimer() {
        PlayerPrincipal principal = new PlayerPrincipal("Alice", "room");

        listener.handleWebSocketConnectListener(connectEvent("session-1", principal));
        listener.handleWebSocketConnectListener(connectEvent("session-2", principal));

        listener.handleWebSocketDisconnectListener(disconnectEvent("session-1", principal));

        assertThat(scheduler.scheduledTasks()).isEmpty();
        verify(lifecycle, never()).markPlayerDisconnected(anyString(), anyString(), anyLong());
    }

    @Test
    void disconnectLastSocketSchedulesGraceTimerWithExactInstant() {
        PlayerPrincipal principal = new PlayerPrincipal("Alice", "room");
        when(lifecycle.gameExists("room")).thenReturn(true);
        when(lifecycle.playerExistsInGame("room", "Alice")).thenReturn(true);

        listener.handleWebSocketConnectListener(connectEvent("session-1", principal));
        listener.handleWebSocketDisconnectListener(disconnectEvent("session-1", principal));

        ArgumentCaptor<Long> deadlineCaptor = ArgumentCaptor.forClass(Long.class);
        verify(lifecycle).markPlayerDisconnected(eq("room"), eq("Alice"), deadlineCaptor.capture());

        assertThat(deadlineCaptor.getValue()).isEqualTo(INITIAL_EPOCH_MS + GRACE_PERIOD_MS);
        assertThat(scheduler.scheduledTasks()).hasSize(1);
        assertThat(scheduler.scheduledTasks().getFirst().startTime())
                .isEqualTo(Instant.ofEpochMilli(deadlineCaptor.getValue()));
    }

    @Test
    void duplicateConnectIsIdempotent() {
        PlayerPrincipal principal = new PlayerPrincipal("Alice", "room");
        when(lifecycle.gameExists("room")).thenReturn(true);
        when(lifecycle.playerExistsInGame("room", "Alice")).thenReturn(true);

        listener.handleWebSocketConnectListener(connectEvent("session-1", principal));
        listener.handleWebSocketConnectListener(connectEvent("session-1", principal));

        listener.handleWebSocketDisconnectListener(disconnectEvent("session-1", principal));
        assertThat(scheduler.scheduledTasks()).hasSize(1);

        listener.handleWebSocketDisconnectListener(disconnectEvent("session-1", principal));
        assertThat(scheduler.scheduledTasks()).hasSize(1);
    }

    @Test
    void disconnectWithSessionOwnershipMismatchIsIgnored() {
        PlayerPrincipal alicePrincipal = new PlayerPrincipal("Alice", "room");
        PlayerPrincipal bobPrincipal = new PlayerPrincipal("Bob", "room");

        listener.handleWebSocketConnectListener(connectEvent("session-1", alicePrincipal));
        listener.handleWebSocketDisconnectListener(disconnectEvent("session-1", bobPrincipal));

        assertThat(scheduler.scheduledTasks()).isEmpty();
        verify(lifecycle, never()).markPlayerDisconnected(anyString(), anyString(), anyLong());

        listener.handleWebSocketDisconnectListener(disconnectEvent("session-1", alicePrincipal));
        assertThat(scheduler.pendingCount()).isEqualTo(1);
    }

    @Test
    void obsoleteGraceCallbackCannotRemoveReconnectedPlayer() {
        PlayerPrincipal principal = new PlayerPrincipal("Alice", "room");

        listener.handleWebSocketConnectListener(connectEvent("session-a", principal));
        listener.handleWebSocketDisconnectListener(disconnectEvent("session-a", principal));

        assertThat(scheduler.scheduledTasks()).hasSize(1);
        ScheduledTask scheduled = scheduler.scheduledTasks().getFirst();

        listener.handleWebSocketConnectListener(connectEvent("session-b", principal));
        assertThat(scheduled.isCancelled()).isTrue();
        assertThat(scheduler.pendingCount()).isZero();

        clock.advanceByMillis(GRACE_PERIOD_MS);
        assertThat(scheduler.runDueTasks(clock)).isZero();

        scheduled.task().run();
        verify(lifecycle, never()).removeDisconnectedPlayer(anyString(), anyString(), any(), anyLong());
    }

    @Test
    void connectFailureInMarkPlayerReconnectedDoesNotRegisterActiveSessionAndAllowsGraceEviction() {
        Room room = new Room("room", "Room", "Alice", 4, 10, 20, 1000, null, clock);
        String membershipId = room.addPlayer("Alice");
        when(roomService.getRoom("room")).thenReturn(room);

        PlayerPrincipal principal = new PlayerPrincipal("Alice", "room", membershipId);
        listener.handleWebSocketConnectListener(connectEvent("session-a", principal));
        listener.handleWebSocketDisconnectListener(disconnectEvent("session-a", principal));

        assertThat(scheduler.scheduledTasks()).hasSize(1);
        ScheduledTask pending = scheduler.scheduledTasks().getFirst();

        doThrow(new RuntimeException("Simulated persistence failure"))
                .when(lifecycle).markPlayerReconnected("room", "Alice");

        assertThatThrownBy(() -> listener.handleWebSocketConnectListener(connectEvent("session-b", principal)))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Simulated persistence failure");

        assertThat(pending.isCancelled()).isFalse();
        assertThat(scheduler.pendingCount()).isEqualTo(1);

        clock.advanceByMillis(GRACE_PERIOD_MS);
        assertThat(scheduler.runDueTasks(clock)).isEqualTo(1);
        verify(lifecycle).removeDisconnectedPlayer(
                "room", "Alice", membershipId, INITIAL_EPOCH_MS + GRACE_PERIOD_MS);
    }

    @Test
    void disconnectSchedulesCleanupEvenIfMarkPlayerDisconnectedThrows() {
        Room room = new Room("room", "Room", "Alice", 4, 10, 20, 1000, null, clock);
        String membershipId = room.addPlayer("Alice");
        when(roomService.getRoom("room")).thenReturn(room);

        PlayerPrincipal principal = new PlayerPrincipal("Alice", "room", membershipId);
        listener.handleWebSocketConnectListener(connectEvent("session-a", principal));

        when(lifecycle.gameExists("room")).thenReturn(true);
        when(lifecycle.playerExistsInGame("room", "Alice")).thenReturn(true);
        doThrow(new RuntimeException("Persistence unavailable"))
                .when(lifecycle).markPlayerDisconnected(eq("room"), eq("Alice"), anyLong());

        listener.handleWebSocketDisconnectListener(disconnectEvent("session-a", principal));
        assertThat(scheduler.pendingCount()).isEqualTo(1);

        clock.advanceByMillis(GRACE_PERIOD_MS);
        assertThat(scheduler.runDueTasks(clock)).isEqualTo(1);
        verify(lifecycle).removeDisconnectedPlayer(
                "room", "Alice", membershipId, INITIAL_EPOCH_MS + GRACE_PERIOD_MS);
    }

    @Test
    void registerInitialConnection_SchedulesGraceTimerWithAcceptanceDeadline() {
        Room room = new Room("room", "Room", "Alice", 4, 10, 20, 1000, null, clock);
        String membershipId = room.addPlayer("Alice");
        when(roomService.getRoom("room")).thenReturn(room);

        long deadline = clock.millis() + GRACE_PERIOD_MS;
        listener.registerInitialConnection("room", "Alice", membershipId, deadline);

        assertThat(scheduler.scheduledTasks()).hasSize(1);
        assertThat(scheduler.scheduledTasks().getFirst().startTime()).isEqualTo(Instant.ofEpochMilli(deadline));

        clock.advanceByMillis(GRACE_PERIOD_MS - 1L);
        assertThat(scheduler.runDueTasks(clock)).isZero();
        verify(lifecycle, never()).removeDisconnectedPlayer(anyString(), anyString(), any(), anyLong());

        clock.advanceByMillis(1L);
        assertThat(scheduler.runDueTasks(clock)).isEqualTo(1);
        verify(rateLimit).cleanUpWs("Alice:room");
        verify(lifecycle).removeDisconnectedPlayer("room", "Alice", membershipId, deadline);
    }

    @Test
    void registerInitialConnection_PlayerAlreadyConnected_DoesNotScheduleTimer() {
        Room room = new Room("room", "Room", "Alice", 4, 10, 20, 1000, null, clock);
        String membershipId = room.addPlayer("Alice");
        when(roomService.getRoom("room")).thenReturn(room);

        listener.handleWebSocketConnectListener(
                connectEvent("session-1", new PlayerPrincipal("Alice", "room", membershipId)));

        listener.registerInitialConnection("room", "Alice", membershipId, clock.millis() + GRACE_PERIOD_MS);

        assertThat(scheduler.scheduledTasks()).isEmpty();
    }

    @Test
    void registerInitialConnection_StaleMembershipOrExistingPendingDisconnect_IsNoOp() {
        Room room = new Room("room", "Room", "Alice", 4, 10, 20, 1000, null, clock);
        String membershipId = room.addPlayer("Alice");
        when(roomService.getRoom("room")).thenReturn(room);

        listener.registerInitialConnection("room", "Alice", "stale-mem", clock.millis() + GRACE_PERIOD_MS);
        assertThat(scheduler.scheduledTasks()).isEmpty();

        long firstDeadline = clock.millis() + GRACE_PERIOD_MS;
        listener.registerInitialConnection("room", "Alice", membershipId, firstDeadline);
        assertThat(scheduler.scheduledTasks()).hasSize(1);

        listener.registerInitialConnection("room", "Alice", membershipId, firstDeadline + 10_000L);
        assertThat(scheduler.scheduledTasks()).hasSize(1);
        assertThat(scheduler.scheduledTasks().getFirst().startTime()).isEqualTo(Instant.ofEpochMilli(firstDeadline));
    }

    @Test
    void scheduleRecoveredDisconnect_ReplacesExistingTimerAndEvictsOrReconcilesActiveSession() {
        String roomId = "room-recovered";
        Room room = new Room(roomId, "Recovered Room", "Alice", 4, 10, 20, 1000, null, clock);
        String aliceMem = room.addPlayer("Alice");
        String bobMem = room.addPlayer("Bob");
        room.setPlayerConnected("Alice", true);
        when(roomService.getRoom(roomId)).thenReturn(room);

        long firstDeadline = clock.millis() + 10_000L;
        listener.scheduleRecoveredDisconnect(roomId, "Alice", firstDeadline);
        assertThat(scheduler.scheduledTasks()).hasSize(1);
        ScheduledTask firstTask = scheduler.scheduledTasks().getFirst();

        long secondDeadline = clock.millis() + 20_000L;
        listener.scheduleRecoveredDisconnect(roomId, "Alice", secondDeadline);
        assertThat(firstTask.isCancelled()).isTrue();
        assertThat(scheduler.pendingCount()).isEqualTo(1);
        TestRoomLocks.runWithRoomLock(roomCoordinator, roomId, () -> {
            assertThat(room.isPlayerConnected("Alice")).isFalse();
            assertThat(room.getAbsenceDeadline("Alice")).isEqualTo(secondDeadline);
        });

        listener.registerSession(new PlayerPrincipal("Bob", roomId, bobMem), "bob-active-session");
        listener.scheduleRecoveredDisconnect(roomId, "Bob", secondDeadline);
        assertThat(scheduler.pendingCount()).isEqualTo(1);
        verify(lifecycle, org.mockito.Mockito.times(2)).markPlayerReconnected(roomId, "Bob");

        clock.advanceByMillis(20_000L);
        assertThat(scheduler.runDueTasks(clock)).isEqualTo(1);
        verify(lifecycle).removeDisconnectedPlayer(roomId, "Alice", aliceMem, secondDeadline);
    }

    @Test
    void disconnectSetsRoomPlayerConnectedFalseAndAbsenceDeadlineUnderRoomLock() {
        Room room = new Room("room", "Room", "Alice", 4, 10, 20, 1000, null, clock);
        String membershipId = room.addPlayer("Alice");
        room.setPlayerConnected("Alice", true);
        when(roomService.getRoom("room")).thenReturn(room);

        PlayerPrincipal principal = new PlayerPrincipal("Alice", "room", membershipId);
        listener.handleWebSocketConnectListener(connectEvent("session-1", principal));
        assertThat(room.isPlayerConnected("Alice")).isTrue();

        listener.handleWebSocketDisconnectListener(disconnectEvent("session-1", principal));

        TestRoomLocks.runWithRoomLock(roomCoordinator, "room", () -> {
            assertThat(room.isPlayerConnected("Alice")).isFalse();
            assertThat(room.getAbsenceDeadline("Alice"))
                    .isEqualTo(scheduler.scheduledTasks().getFirst().startTime().toEpochMilli());
        });
    }

    @Test
    void cleanupDisconnectedUser_WhenPlayerNotInRoomNorGame_SkipsRemoval() {
        PlayerPrincipal principal = new PlayerPrincipal("Alice", "room");
        listener.handleWebSocketConnectListener(connectEvent("session-1", principal));
        listener.handleWebSocketDisconnectListener(disconnectEvent("session-1", principal));

        when(roomService.getRoom("room")).thenReturn(null);
        when(lifecycle.gameExists("room")).thenReturn(false);

        clock.advanceByMillis(GRACE_PERIOD_MS);
        assertThat(scheduler.runDueTasks(clock)).isEqualTo(1);

        verify(rateLimit).cleanUpWs("Alice:room");
        verify(lifecycle, never()).removeDisconnectedPlayer(anyString(), anyString(), any(), anyLong());
    }

    @Test
    void staleMembershipConnectAndDisconnectAreIgnoredWithoutPoisoningActivePlayer() {
        Room room = new Room("room", "Room", "Alice", 4, 10, 20, 1000, null, clock);
        String activeMemId = room.addPlayer("Alice");
        room.setPlayerConnected("Alice", true);
        when(roomService.getRoom("room")).thenReturn(room);

        PlayerPrincipal stalePrincipal = new PlayerPrincipal("Alice", "room", "stale-mem-id");

        listener.handleWebSocketConnectListener(connectEvent("stale-session", stalePrincipal));
        verify(lifecycle, never()).markPlayerReconnected("room", "Alice");

        listener.handleWebSocketDisconnectListener(disconnectEvent("stale-session", stalePrincipal));
        assertThat(room.isPlayerConnected("Alice")).isTrue();
        assertThat(scheduler.scheduledTasks()).isEmpty();
        verify(rateLimit, never()).cleanUpWs(anyString());

        PlayerPrincipal activePrincipal = new PlayerPrincipal("Alice", "room", activeMemId);
        listener.handleWebSocketConnectListener(connectEvent("active-session", activePrincipal));
        verify(lifecycle).markPlayerReconnected("room", "Alice");

        listener.handleWebSocketDisconnectListener(disconnectEvent("stale-session", stalePrincipal));
        assertThat(room.isPlayerConnected("Alice")).isTrue();
        assertThat(scheduler.scheduledTasks()).isEmpty();
        verify(lifecycle, never()).markPlayerDisconnected(anyString(), anyString(), anyLong());

        listener.handleWebSocketDisconnectListener(disconnectEvent("active-session", activePrincipal));
        assertThat(room.isPlayerConnected("Alice")).isFalse();
        assertThat(scheduler.pendingCount()).isEqualTo(1);
    }

    @Test
    void reconnectWinsLockDisconnectSkipsGraceTimerAndPreservesPresence() {
        String roomId = "room-race-1";
        Room room = new Room(roomId, "Race Room", "Alice", 4, 10, 20, 1000, null, clock);
        String membershipId = room.addPlayer("Alice");
        room.setPlayerConnected("Alice", true);
        when(roomService.getRoom(roomId)).thenReturn(room);

        PlayerPrincipal principal = new PlayerPrincipal("Alice", roomId, membershipId);
        listener.registerSession(principal, "session-1");
        listener.registerSession(principal, "session-2");

        listener.handleWebSocketDisconnectListener(disconnectEvent("session-1", principal));

        assertThat(scheduler.scheduledTasks()).isEmpty();
        assertThat(room.isPlayerConnected("Alice")).isTrue();

        listener.handleWebSocketDisconnectListener(disconnectEvent("session-2", principal));
        assertThat(room.isPlayerConnected("Alice")).isFalse();
        assertThat(scheduler.pendingCount()).isEqualTo(1);
    }

    @Test
    void disconnectWinsLockReconnectCancelsTimerAndPreventsEviction() {
        String roomId = "room-race-2";
        Room room = new Room(roomId, "Race Room", "Alice", 4, 10, 20, 1000, null, clock);
        String membershipId = room.addPlayer("Alice");
        room.setPlayerConnected("Alice", true);
        when(roomService.getRoom(roomId)).thenReturn(room);
        when(lifecycle.gameExists(roomId)).thenReturn(true);
        when(lifecycle.playerExistsInGame(roomId, "Alice")).thenReturn(true);

        PlayerPrincipal principal = new PlayerPrincipal("Alice", roomId, membershipId);
        listener.registerSession(principal, "session-1");
        listener.handleWebSocketDisconnectListener(disconnectEvent("session-1", principal));

        assertThat(room.isPlayerConnected("Alice")).isFalse();
        assertThat(scheduler.scheduledTasks()).hasSize(1);
        ScheduledTask firstGrace = scheduler.scheduledTasks().getFirst();

        listener.registerSession(principal, "session-2");

        assertThat(firstGrace.isCancelled()).isTrue();
        assertThat(scheduler.pendingCount()).isZero();

        clock.advanceByMillis(GRACE_PERIOD_MS);
        assertThat(scheduler.runDueTasks(clock)).isZero();
        verify(lifecycle, never()).removeDisconnectedPlayer(any(), any(), any(), anyLong());

        TestRoomLocks.runWithRoomLock(roomCoordinator, roomId, () -> room.setPlayerConnected("Alice", true));
        listener.handleWebSocketDisconnectListener(disconnectEvent("session-2", principal));
        assertThat(room.isPlayerConnected("Alice")).isFalse();
        assertThat(scheduler.pendingCount()).isEqualTo(1);
    }

    @Test
    void concurrentReconnectAndDisconnectPreservesPresenceWithoutDeadlock() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            int iterations = 15;
            for (int i = 0; i < iterations; i++) {
                scheduler.clear();
                clock.setMillis(INITIAL_EPOCH_MS + i * 100_000L);

                String roomId = "room-concurrent-" + i;
                Room room = new Room(roomId, "Concurrent Room", "Alice", 4, 10, 20, 1000, null, clock);
                String membershipId = room.addPlayer("Alice");
                room.setPlayerConnected("Alice", true);
                when(roomService.getRoom(roomId)).thenReturn(room);

                PlayerPrincipal principal = new PlayerPrincipal("Alice", roomId, membershipId);
                String origSession = "session-orig-" + i;
                String newSession = "session-new-" + i;
                listener.registerSession(principal, origSession);

                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<?> disconnectFuture = executor.submit(() -> {
                    barrier.await(2, TimeUnit.SECONDS);
                    listener.handleWebSocketDisconnectListener(disconnectEvent(origSession, principal));
                    return null;
                });
                Future<?> connectFuture = executor.submit(() -> {
                    barrier.await(2, TimeUnit.SECONDS);
                    listener.registerSession(principal, newSession);
                    return null;
                });

                disconnectFuture.get(3, TimeUnit.SECONDS);
                connectFuture.get(3, TimeUnit.SECONDS);

                assertThat(scheduler.pendingCount()).isZero();
                for (ScheduledTask task : scheduler.scheduledTasks()) {
                    assertThat(task.isCancelled()).isTrue();
                }
                clock.advanceByMillis(GRACE_PERIOD_MS);
                assertThat(scheduler.runDueTasks(clock)).isZero();
                verify(lifecycle, never()).removeDisconnectedPlayer(eq(roomId), any(), any(), anyLong());
                verify(rateLimit, never()).cleanUpWs("Alice:" + roomId);

                int tasksBefore = scheduler.scheduledTasks().size();
                listener.handleWebSocketDisconnectListener(disconnectEvent(origSession, principal));
                assertThat(scheduler.scheduledTasks()).hasSize(tasksBefore);
                assertThat(scheduler.pendingCount()).isZero();

                listener.handleWebSocketDisconnectListener(disconnectEvent(newSession, principal));
                assertThat(scheduler.pendingCount()).isEqualTo(1);
            }
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void roomDestructionCleansUpAllSessionsAndRegistrationsIncludingPlayerNamesWithColons() {
        String roomId = "room-destroy-1";
        Room room = new Room(roomId, "Destroy Room", "Host", 4, 10, 20, 1000, null, clock);
        String hostMem = room.addPlayer("Host");
        String aliceMem = room.addPlayer("Alice:anything");
        String bobMem = room.addPlayer("Bob");
        when(roomService.getRoom(roomId)).thenReturn(room);

        PlayerPrincipal hostPrincipal = new PlayerPrincipal("Host", roomId, hostMem);
        PlayerPrincipal alicePrincipal = new PlayerPrincipal("Alice:anything", roomId, aliceMem);
        PlayerPrincipal bobPrincipal = new PlayerPrincipal("Bob", roomId, bobMem);

        listener.registerSession(hostPrincipal, "host-s1");
        listener.registerSession(hostPrincipal, "host-s2");

        listener.registerSession(alicePrincipal, "alice-s1");
        listener.handleWebSocketDisconnectListener(disconnectEvent("alice-s1", alicePrincipal));
        assertThat(scheduler.pendingCount()).isEqualTo(1);
        ScheduledTask aliceGrace = scheduler.scheduledTasks().getFirst();

        listener.registerSession(bobPrincipal, "bob-s1");

        listener.publishRoomDestroyed(roomId);
        assertThat(scheduler.runDueTasks(clock)).isEqualTo(1);

        verify(lifecycle).cancelTimers(roomId, false);
        assertThat(aliceGrace.isCancelled()).isTrue();
        verify(rateLimit).cleanUpWs("Host:" + roomId);
        verify(rateLimit).cleanUpWs("Alice:anything:" + roomId);
        verify(rateLimit).cleanUpWs("Bob:" + roomId);

        clock.advanceByMillis(GRACE_PERIOD_MS);
        assertThat(scheduler.runDueTasks(clock)).isZero();
        verify(lifecycle, never()).removeDisconnectedPlayer(anyString(), anyString(), anyString(), anyLong());

        int scheduledCount = scheduler.scheduledTasks().size();
        for (String sid : new String[] {"host-s1", "host-s2", "alice-s1", "bob-s1"}) {
            listener.handleWebSocketDisconnectListener(disconnectEvent(sid, null));
        }
        assertThat(scheduler.scheduledTasks()).hasSize(scheduledCount);
    }

    @Test
    void repeatedConnectionAndDestructionCyclesReleaseAllRegistrationsWithoutLeak() {
        int cycles = 15;
        for (int i = 0; i < cycles; i++) {
            scheduler.clear();
            String roomId = "churn-room-" + i;
            Room room = new Room(roomId, "Churn Room", "Host", 4, 10, 20, 1000, null, clock);
            String hostMem = room.addPlayer("Host");
            String guestMem = room.addPlayer("Guest");
            when(roomService.getRoom(roomId)).thenReturn(room);

            PlayerPrincipal host = new PlayerPrincipal("Host", roomId, hostMem);
            PlayerPrincipal guest = new PlayerPrincipal("Guest", roomId, guestMem);
            listener.registerSession(host, "host-sess-" + i);
            listener.registerSession(guest, "guest-sess-" + i);

            listener.handleWebSocketDisconnectListener(disconnectEvent("guest-sess-" + i, guest));
            ScheduledTask guestGrace = scheduler.scheduledTasks().getFirst();

            listener.publishRoomDestroyed(roomId);
            assertThat(scheduler.runDueTasks(clock)).isEqualTo(1);

            assertThat(guestGrace.isCancelled()).isTrue();
            assertThat(scheduler.pendingCount()).isZero();
            verify(rateLimit).cleanUpWs("Host:" + roomId);
            verify(rateLimit).cleanUpWs("Guest:" + roomId);

            int tasksBefore = scheduler.scheduledTasks().size();
            listener.handleWebSocketDisconnectListener(disconnectEvent("host-sess-" + i, null));
            listener.handleWebSocketDisconnectListener(disconnectEvent("guest-sess-" + i, null));
            assertThat(scheduler.scheduledTasks()).hasSize(tasksBefore);
        }
    }

    @Test
    void roomCoordinatorReentrancyAndCrossRoomRejectionWithinSessionOperations() {
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

        // Session mappings registered inside separate room locks drive subsequent principal-less disconnects
        listener.handleWebSocketDisconnectListener(disconnectEvent("session-a1", null));
        listener.handleWebSocketDisconnectListener(disconnectEvent("session-b1", null));
        assertThat(scheduler.pendingCount()).isEqualTo(2);
    }

    @Test
    void cancelRoomPendingDisconnectsRequiresRoomLockAndCancelsOnlyMatchingRoom() {
        String roomA = "room-pending-A";
        String roomB = "room-pending-B";
        long deadline = clock.millis() + 30_000L;

        listener.scheduleRecoveredDisconnect(roomA, "Alice:colon", deadline);
        listener.scheduleRecoveredDisconnect(roomB, "Bob", deadline);
        assertThat(scheduler.scheduledTasks()).hasSize(2);
        ScheduledTask taskA = scheduler.scheduledTasks().get(0);
        ScheduledTask taskB = scheduler.scheduledTasks().get(1);

        assertThatThrownBy(() -> listener.cancelRoomPendingDisconnects(roomA))
                .isInstanceOf(IllegalStateException.class);

        TestRoomLocks.runWithRoomLock(roomCoordinator, roomA, () -> listener.cancelRoomPendingDisconnects(roomA));

        assertThat(taskA.isCancelled()).isTrue();
        assertThat(taskB.isCancelled()).isFalse();
        assertThat(scheduler.pendingCount()).isEqualTo(1);

        Room bobRoom = new Room(roomB, "Room B", "Bob", 4, 10, 20, 1000, null, clock);
        bobRoom.addPlayer("Bob");
        when(roomService.getRoom(roomB)).thenReturn(bobRoom);

        clock.advanceByMillis(30_000L);
        assertThat(scheduler.runDueTasks(clock)).isEqualTo(1);

        verify(lifecycle, never()).removeDisconnectedPlayer(eq(roomA), anyString(), any(), anyLong());
        verify(lifecycle).removeDisconnectedPlayer(roomB, "Bob", null, deadline);
    }

    @Test
    void registerSessionDropsSocketsFromOlderMembership() {
        String roomId = "room-replace-mem";
        Room room = new Room(roomId, "Room", "Alice", 4, 10, 20, 1000, null, clock);
        String firstMembership = room.addPlayer("Alice");
        when(roomService.getRoom(roomId)).thenReturn(room);

        listener.registerSession(new PlayerPrincipal("Alice", roomId, firstMembership), "session-old");

        String secondMembership = TestRoomLocks.withRoomLock(roomCoordinator, roomId, () -> {
            room.removePlayer("Alice");
            String membership = room.addPlayer("Alice");
            room.setPlayerConnected("Alice", true);
            return membership;
        });
        listener.registerSession(new PlayerPrincipal("Alice", roomId, secondMembership), "session-new");

        listener.handleWebSocketDisconnectListener(disconnectEvent("session-old", null));
        assertThat(room.isPlayerConnected("Alice")).isTrue();
        assertThat(scheduler.pendingCount()).isZero();

        listener.handleWebSocketDisconnectListener(
                disconnectEvent("session-new", new PlayerPrincipal("Alice", roomId, secondMembership)));
        assertThat(room.isPlayerConnected("Alice")).isFalse();
        assertThat(scheduler.pendingCount()).isEqualTo(1);
    }

    @Test
    void subscribeAndUnsubscribeEventsHandleAuthenticatedAndAnonymousPrincipals() {
        PlayerPrincipal principal = new PlayerPrincipal("Alice", "room-sub", "mem-1");

        StompHeaderAccessor authSub = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        authSub.setSessionId("s-sub");
        authSub.setUser(principal);
        authSub.setDestination("/room/room-sub");
        assertThatCode(() -> listener.handleSubscribe(
                new SessionSubscribeEvent(this, MessageBuilder.createMessage(new byte[0], authSub.getMessageHeaders()))))
                .doesNotThrowAnyException();

        StompHeaderAccessor anonSub = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        anonSub.setSessionId("s-anon");
        anonSub.setDestination("/room/room-sub");
        assertThatCode(() -> listener.handleSubscribe(
                new SessionSubscribeEvent(this, MessageBuilder.createMessage(new byte[0], anonSub.getMessageHeaders()))))
                .doesNotThrowAnyException();

        StompHeaderAccessor authUnsub = StompHeaderAccessor.create(StompCommand.UNSUBSCRIBE);
        authUnsub.setSessionId("s-sub");
        authUnsub.setUser(principal);
        authUnsub.setSubscriptionId("sub-1");
        assertThatCode(() -> listener.handleUnsubscribe(
                new SessionUnsubscribeEvent(this, MessageBuilder.createMessage(new byte[0], authUnsub.getMessageHeaders()))))
                .doesNotThrowAnyException();

        StompHeaderAccessor anonUnsub = StompHeaderAccessor.create(StompCommand.UNSUBSCRIBE);
        anonUnsub.setSessionId("s-anon");
        anonUnsub.setSubscriptionId("sub-2");
        assertThatCode(() -> listener.handleUnsubscribe(
                new SessionUnsubscribeEvent(this, MessageBuilder.createMessage(new byte[0], anonUnsub.getMessageHeaders()))))
                .doesNotThrowAnyException();

        assertThat(scheduler.scheduledTasks()).isEmpty();
    }

    @Test
    void onRoomMemberRegisteredAdapterStartsInitialGraceTimer() {
        String roomId = "room-event-register";
        Room room = new Room(roomId, "Events Room", "Alice", 4, 10, 20, 1000, null, clock);
        String aliceMem = room.addPlayer("Alice");
        when(roomService.getRoom(roomId)).thenReturn(room);

        long deadline = clock.millis() + GRACE_PERIOD_MS;
        listener.onRoomMemberRegistered(new RoomMemberRegistered(roomId, "Alice", aliceMem, deadline));
        assertThat(scheduler.pendingCount()).isEqualTo(1);

        clock.advanceByMillis(GRACE_PERIOD_MS);
        assertThat(scheduler.runDueTasks(clock)).isEqualTo(1);
        verify(rateLimit).cleanUpWs("Alice:" + roomId);
        verify(lifecycle).removeDisconnectedPlayer(roomId, "Alice", aliceMem, deadline);
    }

    @Test
    void onRoomMembershipEndedAdapterSchedulesAndRunsMembershipCleanup() {
        String roomId = "room-event-ended";
        Room room = new Room(roomId, "Events Room", "Bob", 4, 10, 20, 1000, null, clock);
        String bobMem = room.addPlayer("Bob");
        when(roomService.getRoom(roomId)).thenReturn(room);

        listener.registerSession(new PlayerPrincipal("Bob", roomId, bobMem), "bob-sess");
        TestRoomLocks.runWithRoomLock(roomCoordinator, roomId, () -> room.removePlayer("Bob"));

        listener.onRoomMembershipEnded(new RoomMembershipEnded(roomId, "Bob", bobMem));
        assertThat(scheduler.runAllPending()).isEqualTo(1);
        verify(rateLimit).cleanUpWs("Bob:" + roomId);
    }

    @Test
    void onRoomDestroyedAdapterSchedulesAndRunsRoomCleanup() {
        String roomId = "room-event-destroyed";
        Room room = new Room(roomId, "Events Room", "Alice", 4, 10, 20, 1000, null, clock);
        String aliceMem = room.addPlayer("Alice");
        when(roomService.getRoom(roomId)).thenReturn(room);

        listener.registerSession(new PlayerPrincipal("Alice", roomId, aliceMem), "alice-sess");
        listener.onRoomDestroyed(new RoomDestroyed(roomId));
        assertThat(scheduler.runAllPending()).isEqualTo(1);
        verify(lifecycle).cancelTimers(roomId, false);
        verify(rateLimit).cleanUpWs("Alice:" + roomId);
    }

    @Test
    void scheduleCleanupDropsTaskWhenSchedulerRejects() {
        scheduler.setRejectNext(true);
        assertThatCode(() -> listener.onRoomDestroyed(new RoomDestroyed("room-reject-1")))
                .doesNotThrowAnyException();

        scheduler.setRejectNext(true);
        assertThatCode(() -> listener.onRoomMembershipEnded(
                new RoomMembershipEnded("room-reject-2", "Alice", "mem-1")))
                .doesNotThrowAnyException();

        assertThat(scheduler.pendingCount()).isZero();
    }

    @Test
    void lateCancelRaceBeforeFutureAttachCancelsReturnedFutureAndPreventsEviction() {
        String roomId = "room-late-cancel";
        Room room = new Room(roomId, "Late Cancel Room", "Alice", 4, 10, 20, 1000, null, clock);
        String membershipId = room.addPlayer("Alice");
        when(roomService.getRoom(roomId)).thenReturn(room);

        PlayerPrincipal principal = new PlayerPrincipal("Alice", roomId, membershipId);
        listener.registerSession(principal, "session-1");

        scheduler.onNextSchedule(() -> listener.registerSession(principal, "session-2"));

        listener.handleWebSocketDisconnectListener(disconnectEvent("session-1", principal));

        assertThat(scheduler.scheduledTasks()).hasSize(1);
        assertThat(scheduler.scheduledTasks().getFirst().isCancelled()).isTrue();
        assertThat(scheduler.pendingCount()).isZero();

        clock.advanceByMillis(GRACE_PERIOD_MS);
        assertThat(scheduler.runDueTasks(clock)).isZero();
        verify(lifecycle, never()).removeDisconnectedPlayer(anyString(), anyString(), any(), anyLong());
    }

    @Test
    void graceExpiryRemovesDisconnectedPlayerWithMembershipAndDeadline() {
        String roomId = "room-grace-expiry";
        Room room = new Room(roomId, "Grace Room", "Alice", 4, 10, 20, 1000, null, clock);
        String membershipId = room.addPlayer("Alice");
        when(roomService.getRoom(roomId)).thenReturn(room);
        when(lifecycle.gameExists(roomId)).thenReturn(true);
        when(lifecycle.playerExistsInGame(roomId, "Alice")).thenReturn(true);

        PlayerPrincipal principal = new PlayerPrincipal("Alice", roomId, membershipId);
        listener.registerSession(principal, "session-1");
        listener.handleWebSocketDisconnectListener(disconnectEvent("session-1", principal));

        long deadline = TestRoomLocks.withRoomLock(roomCoordinator, roomId,
                () -> room.getAbsenceDeadline("Alice"));

        clock.advanceByMillis(GRACE_PERIOD_MS - 1L);
        assertThat(scheduler.runDueTasks(clock)).isZero();
        verify(lifecycle, never()).removeDisconnectedPlayer(anyString(), anyString(), any(), anyLong());

        clock.advanceByMillis(1L);
        assertThat(scheduler.runDueTasks(clock)).isEqualTo(1);
        verify(rateLimit).cleanUpWs("Alice:" + roomId);
        verify(lifecycle).removeDisconnectedPlayer(roomId, "Alice", membershipId, deadline);
    }

    private SessionConnectEvent connectEvent(String sessionId, PlayerPrincipal principal) {
        return new SessionConnectEvent(this, message(StompCommand.CONNECT, sessionId, principal));
    }

    private SessionDisconnectEvent disconnectEvent(String sessionId, PlayerPrincipal principal) {
        return new SessionDisconnectEvent(
                this, message(StompCommand.DISCONNECT, sessionId, principal), sessionId, null);
    }

    private static Message<byte[]> message(StompCommand command, String sessionId, PlayerPrincipal principal) {
        StompHeaderAccessor headers = StompHeaderAccessor.create(command);
        headers.setSessionId(sessionId);
        headers.setUser(principal);
        return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    }
}
