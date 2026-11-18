package com.pokergame.config;

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
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("unit")
class WebSocketEventListenerTest {

    private static final long GRACE_PERIOD_MS = 60_000L;
    private static final RoomTimingProperties ROOM_TIMINGS = TestTimings.room(GRACE_PERIOD_MS);

    private RoomService roomService;
    private GameLifecycleService lifecycle;
    private RateLimitService rateLimit;
    private MutableClock clock;
    private DeterministicTaskScheduler scheduler;
    private RoomCoordinator roomCoordinator;
    private WebSocketEventListener listener;

    @BeforeEach
    void setUp() {
        roomService = mock(RoomService.class);
        lifecycle = mock(GameLifecycleService.class);
        rateLimit = mock(RateLimitService.class);
        clock = new MutableClock(System.currentTimeMillis());
        scheduler = new DeterministicTaskScheduler(clock);
        roomCoordinator = new RoomCoordinator();
        listener = new WebSocketEventListener(
                roomService, lifecycle, rateLimit, scheduler, roomCoordinator, ROOM_TIMINGS);
    }

    @Test
    void reconnectAndRoomDestructionCompleteWithoutOppositeLockAcquisition() throws Exception {
        PlayerPrincipal principal = new PlayerPrincipal("Alice", "room");
        listener.registerSession(principal, "original-session");

        CountDownLatch cleanupInsideLock = new CountDownLatch(1);
        CountDownLatch releaseCleanup = new CountDownLatch(1);

        doAnswer(invocation -> {
            // Models same-room re-entry by the durable service proxy.
            try (LockLease _ = roomCoordinator.acquire("room")) {
                assertTrue(roomCoordinator.isHeldByCurrentThread("room"));
                return null;
            }
        }).when(lifecycle).markPlayerReconnected("room", "Alice");

        doAnswer(invocation -> {
            cleanupInsideLock.countDown();
            assertTrue(releaseCleanup.await(2, TimeUnit.SECONDS));
            return null;
        }).when(lifecycle).cancelTimers("room", false);

        listener.publishRoomDestroyed("room");

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Void> cleanup = executor.submit(() -> {
                scheduler.runAllPending();
                return null;
            });
            assertTrue(cleanupInsideLock.await(2, TimeUnit.SECONDS));

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
        }

        verify(lifecycle).cancelTimers("room", false);
        verify(rateLimit).cleanUpWs("Alice:room");

        // Replacement session registered after cleanup remains active.
        listener.handleWebSocketDisconnectListener(disconnectEvent("replacement-session", principal));
        assertEquals(1, scheduler.pendingCount());
    }

    /** An ended-membership cleanup that runs late must not evict a player who has since re-joined. */
    @Test
    void lateMembershipEndedDoesNotEvictRejoinedPlayer() {
        Room room = new Room("room", "Room", "Bob", 4, 10, 20, 500, null);
        String firstMembership = room.addPlayer("Bob");
        when(roomService.getRoom("room")).thenReturn(room);
        listener.registerSession(new PlayerPrincipal("Bob", "room", firstMembership), "bob-session-1");

        // The membership-ended cleanup is scheduled but has not run yet.
        listener.publishMembershipEnded("room", "Bob", firstMembership);
        assertEquals(1, scheduler.pendingCount());

        // Meanwhile Bob leaves and re-joins with a new membership and connects again.
        String secondMembership = TestRoomLocks.withRoomLock(roomCoordinator, "room", () -> {
            room.removePlayer("Bob");
            String membership = room.addPlayer("Bob");
            room.setPlayerConnected("Bob", true);
            return membership;
        });
        assertNotEquals(firstMembership, secondMembership);
        listener.registerSession(new PlayerPrincipal("Bob", "room", secondMembership), "bob-session-2");

        assertEquals(1, scheduler.runAllPending());

        TestRoomLocks.withRoomLock(roomCoordinator, "room", () -> {
            assertTrue(room.hasPlayer("Bob"));
            assertEquals(secondMembership, room.getMembershipId("Bob"));
            return null;
        });
        verify(rateLimit, never()).cleanUpWs("Bob:room");

        // Disconnecting the retired session does not disturb Bob's active rejoined session.
        listener.handleWebSocketDisconnectListener(
                disconnectEvent("bob-session-1", new PlayerPrincipal("Bob", "room", firstMembership)));
        assertTrue(room.isPlayerConnected("Bob"));
        assertEquals(0, scheduler.pendingCount());

        // Disconnecting the rejoined session triggers normal disconnect grace scheduling.
        listener.handleWebSocketDisconnectListener(
                disconnectEvent("bob-session-2", new PlayerPrincipal("Bob", "room", secondMembership)));
        assertFalse(room.isPlayerConnected("Bob"));
        assertEquals(1, scheduler.pendingCount());
    }

    @Test
    void connectReconcilesRecoveredDisconnectedPlayerEvenWithoutPendingRuntimeFuture() {
        when(lifecycle.gameExists("room")).thenReturn(true);
        when(lifecycle.playerExistsInGame("room", "Alice")).thenReturn(true);

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

        assertThat(scheduler.scheduledTasks()).hasSize(1);
        assertEquals(Instant.ofEpochMilli(deadlineCaptor.getValue()),
                scheduler.scheduledTasks().getFirst().startTime(),
                "TaskScheduler instant must exactly match the domain deadline epoch ms");
    }

    @Test
    void duplicateConnectIsIdempotent() {
        PlayerPrincipal principal = new PlayerPrincipal("Alice", "room");
        when(lifecycle.gameExists("room")).thenReturn(true);
        when(lifecycle.playerExistsInGame("room", "Alice")).thenReturn(true);

        // Connect session-1 twice
        listener.handleWebSocketConnectListener(connectEvent("session-1", principal));
        listener.handleWebSocketConnectListener(connectEvent("session-1", principal));

        // Disconnecting session-1 once transitions to 0 and schedules
        listener.handleWebSocketDisconnectListener(disconnectEvent("session-1", principal));
        assertThat(scheduler.scheduledTasks()).hasSize(1);

        // A second disconnect for session-1 is a duplicate/already-retired disconnect and produces no new schedule
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

        // Alice's session remains active so her own disconnect schedules cleanup.
        listener.handleWebSocketDisconnectListener(disconnectEvent("session-1", alicePrincipal));
        assertEquals(1, scheduler.pendingCount());
    }

    @Test
    void obsoleteGraceCallbackCannotRemoveReconnectedPlayer() {
        PlayerPrincipal principal = new PlayerPrincipal("Alice", "room");

        listener.handleWebSocketConnectListener(connectEvent("session-a", principal));
        listener.handleWebSocketDisconnectListener(disconnectEvent("session-a", principal));

        assertThat(scheduler.scheduledTasks()).hasSize(1);
        ScheduledTask scheduled = scheduler.scheduledTasks().getFirst();

        listener.handleWebSocketConnectListener(connectEvent("session-b", principal));
        assertTrue(scheduled.isCancelled());
        assertEquals(0, scheduler.pendingCount());

        clock.advanceByMillis(GRACE_PERIOD_MS + 5_000L);
        assertEquals(0, scheduler.runDueTasks(clock));

        // Even if an already-dispatched callback runs, its stale/cancelled guard prevents eviction.
        scheduled.task().run();
        verify(lifecycle, never()).removeDisconnectedPlayer(anyString(), anyString(), any(), anyLong());
    }

    @Test
    void connectFailureInMarkPlayerReconnectedLeavesPendingDisconnectActive() {
        PlayerPrincipal principal = new PlayerPrincipal("Alice", "room");

        listener.handleWebSocketConnectListener(connectEvent("session-a", principal));
        listener.handleWebSocketDisconnectListener(disconnectEvent("session-a", principal));

        assertThat(scheduler.scheduledTasks()).hasSize(1);
        ScheduledTask pending = scheduler.scheduledTasks().getFirst();

        when(lifecycle.gameExists("room")).thenReturn(true);
        when(lifecycle.playerExistsInGame("room", "Alice")).thenReturn(true);
        doThrow(new RuntimeException("Simulated persistence failure"))
                .when(lifecycle).markPlayerReconnected("room", "Alice");

        try {
            listener.handleWebSocketConnectListener(connectEvent("session-b", principal));
        } catch (RuntimeException ignored) {
        }

        assertFalse(pending.isCancelled());
        assertEquals(1, scheduler.pendingCount());
    }

    @Test
    void disconnectSchedulesCleanupEvenIfMarkPlayerDisconnectedThrows() {
        Room room = new Room("room", "Room", "Alice", 4, 10, 20, 1000, null);
        String membershipId = room.addPlayer("Alice");
        when(roomService.getRoom("room")).thenReturn(room);

        PlayerPrincipal principal = new PlayerPrincipal("Alice", "room", membershipId);
        listener.handleWebSocketConnectListener(connectEvent("session-a", principal));

        when(lifecycle.gameExists("room")).thenReturn(true);
        when(lifecycle.playerExistsInGame("room", "Alice")).thenReturn(true);
        doThrow(new RuntimeException("Persistence unavailable"))
                .when(lifecycle).markPlayerDisconnected(eq("room"), eq("Alice"), anyLong());

        listener.handleWebSocketDisconnectListener(disconnectEvent("session-a", principal));
        assertEquals(1, scheduler.pendingCount());

        clock.advanceByMillis(GRACE_PERIOD_MS + 5_000L);
        assertEquals(1, scheduler.runDueTasks(clock));
        verify(lifecycle).removeDisconnectedPlayer(eq("room"), eq("Alice"), eq(membershipId), anyLong());
    }

    @Test
    void registerInitialConnection_SchedulesGraceTimerWithAcceptanceDeadline() {
        Room room = new Room("room", "Room", "Alice", 4, 10, 20, 1000, null);
        String membershipId = room.addPlayer("Alice");
        when(roomService.getRoom("room")).thenReturn(room);

        long deadline = clock.millis() + GRACE_PERIOD_MS;
        listener.registerInitialConnection("room", "Alice", membershipId, deadline);

        assertThat(scheduler.scheduledTasks()).hasSize(1);
        assertEquals(Instant.ofEpochMilli(deadline), scheduler.scheduledTasks().getFirst().startTime());

        clock.advanceByMillis(GRACE_PERIOD_MS - 1L);
        assertEquals(0, scheduler.runDueTasks(clock));
        verify(lifecycle, never()).removeDisconnectedPlayer(anyString(), anyString(), any(), anyLong());

        clock.advanceByMillis(1L);
        assertEquals(1, scheduler.runDueTasks(clock));
        verify(rateLimit).cleanUpWs("Alice:room");
        verify(lifecycle).removeDisconnectedPlayer("room", "Alice", membershipId, deadline);
    }

    @Test
    void registerInitialConnection_PlayerAlreadyConnected_DoesNotScheduleTimer() {
        Room room = new Room("room", "Room", "Alice", 4, 10, 20, 1000, null);
        String membershipId = room.addPlayer("Alice");
        when(roomService.getRoom("room")).thenReturn(room);

        listener.handleWebSocketConnectListener(
                connectEvent("session-1", new PlayerPrincipal("Alice", "room", membershipId)));

        listener.registerInitialConnection("room", "Alice", membershipId, clock.millis() + GRACE_PERIOD_MS);

        assertThat(scheduler.scheduledTasks()).isEmpty();
    }

    @Test
    void disconnectSetsRoomPlayerConnectedFalseAndAbsenceDeadlineUnderRoomLock() {
        Room room = new Room("room", "Room", "Alice", 4, 10, 20, 1000, null);
        String membershipId = room.addPlayer("Alice");
        room.setPlayerConnected("Alice", true);
        when(roomService.getRoom("room")).thenReturn(room);

        PlayerPrincipal principal = new PlayerPrincipal("Alice", "room", membershipId);
        listener.handleWebSocketConnectListener(connectEvent("session-1", principal));
        assertTrue(room.isPlayerConnected("Alice"));

        listener.handleWebSocketDisconnectListener(disconnectEvent("session-1", principal));

        TestRoomLocks.withRoomLock(roomCoordinator, "room", () -> {
            assertFalse(room.isPlayerConnected("Alice"),
                    "Player connection flag in room aggregate must be cleared on disconnect");
            assertNotNull(room.getAbsenceDeadline("Alice"),
                    "Room absence deadline must be recorded on disconnect");
            assertEquals(scheduler.scheduledTasks().getFirst().startTime().toEpochMilli(),
                    room.getAbsenceDeadline("Alice"),
                    "Room absence deadline must match the scheduled grace expiry instant");
            return null;
        });
    }

    @Test
    void cleanupDisconnectedUser_WhenPlayerNotInRoomNorGame_SkipsRemoval() {
        PlayerPrincipal principal = new PlayerPrincipal("Alice", "room");
        listener.handleWebSocketConnectListener(connectEvent("session-1", principal));
        listener.handleWebSocketDisconnectListener(disconnectEvent("session-1", principal));

        when(roomService.getRoom("room")).thenReturn(null);
        when(lifecycle.gameExists("room")).thenReturn(false);

        clock.advanceByMillis(GRACE_PERIOD_MS + 5_000L);
        assertEquals(1, scheduler.runDueTasks(clock));

        verify(rateLimit).cleanUpWs("Alice:room");
        verify(lifecycle, never()).removeDisconnectedPlayer(anyString(), anyString(), any(), anyLong());
    }

    @Test
    void staleMembershipConnectAndDisconnectAreIgnoredWithoutPoisoningActivePlayer() {
        Room room = new Room("room", "Room", "Alice", 4, 10, 20, 1000, null);
        String activeMemId = room.addPlayer("Alice");
        room.setPlayerConnected("Alice", true);
        when(roomService.getRoom("room")).thenReturn(room);

        PlayerPrincipal stalePrincipal = new PlayerPrincipal("Alice", "room", "stale-mem-id");

        // Stale connect is ignored (and disconnecting it has no effect)
        listener.handleWebSocketConnectListener(connectEvent("stale-session", stalePrincipal));
        verify(lifecycle, never()).markPlayerReconnected("room", "Alice");

        listener.handleWebSocketDisconnectListener(disconnectEvent("stale-session", stalePrincipal));
        assertTrue(room.isPlayerConnected("Alice"));
        assertThat(scheduler.scheduledTasks()).isEmpty();
        verify(rateLimit, never()).cleanUpWs(anyString());

        // Active session connects
        PlayerPrincipal activePrincipal = new PlayerPrincipal("Alice", "room", activeMemId);
        listener.handleWebSocketConnectListener(connectEvent("active-session", activePrincipal));
        verify(lifecycle).markPlayerReconnected("room", "Alice");

        // Stale disconnect is ignored and does not mark Alice disconnected or schedule grace timer
        listener.handleWebSocketDisconnectListener(disconnectEvent("stale-session", stalePrincipal));
        assertTrue(room.isPlayerConnected("Alice"));
        assertThat(scheduler.scheduledTasks()).isEmpty();
        verify(lifecycle, never()).markPlayerDisconnected(anyString(), anyString(), anyLong());

        // Active disconnect marks Alice disconnected and schedules grace timer
        listener.handleWebSocketDisconnectListener(disconnectEvent("active-session", activePrincipal));
        assertFalse(room.isPlayerConnected("Alice"));
        assertEquals(1, scheduler.pendingCount());
    }

    @Test
    void reconnectWinsLockDisconnectSkipsGraceTimerAndPreservesPresence() {
        String roomId = "room-race-1";
        Room room = new Room(roomId, "Race Room", "Alice", 4, 10, 20, 1000, null);
        String membershipId = room.addPlayer("Alice");
        room.setPlayerConnected("Alice", true);
        when(roomService.getRoom(roomId)).thenReturn(room);

        PlayerPrincipal principal = new PlayerPrincipal("Alice", roomId, membershipId);
        listener.registerSession(principal, "session-1");
        listener.registerSession(principal, "session-2");

        listener.handleWebSocketDisconnectListener(disconnectEvent("session-1", principal));

        assertThat(scheduler.scheduledTasks()).isEmpty();
        assertThat(room.isPlayerConnected("Alice")).isTrue();

        // Second session stays registered until explicitly disconnected
        listener.handleWebSocketDisconnectListener(disconnectEvent("session-2", principal));
        assertThat(room.isPlayerConnected("Alice")).isFalse();
        assertEquals(1, scheduler.pendingCount());
    }

    @Test
    void disconnectWinsLockReconnectCancelsTimerAndPreventsEviction() {
        String roomId = "room-race-2";
        Room room = new Room(roomId, "Race Room", "Alice", 4, 10, 20, 1000, null);
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

        assertTrue(firstGrace.isCancelled());
        assertEquals(0, scheduler.pendingCount());

        clock.advanceByMillis(GRACE_PERIOD_MS + 5_000L);
        assertEquals(0, scheduler.runDueTasks(clock));
        verify(lifecycle, never()).removeDisconnectedPlayer(any(), any(), any(), anyLong());

        // session-2 stays registered after the grace deadline passes
        TestRoomLocks.withRoomLock(roomCoordinator, roomId, () -> {
            room.setPlayerConnected("Alice", true);
            return null;
        });
        listener.handleWebSocketDisconnectListener(disconnectEvent("session-2", principal));
        assertThat(room.isPlayerConnected("Alice")).isFalse();
        assertEquals(1, scheduler.pendingCount());
    }

    @Test
    void concurrentReconnectAndDisconnectPreservesPresenceWithoutDeadlock() throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            int iterations = 40;
            for (int i = 0; i < iterations; i++) {
                scheduler.clear();
                clock.setMillis(System.currentTimeMillis());

                String roomId = "room-concurrent-" + i;
                Room room = new Room(roomId, "Concurrent Room", "Alice", 4, 10, 20, 1000, null);
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

                // Whether disconnect or reconnect won the room lock, no pending disconnect remains active.
                assertEquals(0, scheduler.pendingCount());
                for (ScheduledTask task : scheduler.scheduledTasks()) {
                    assertTrue(task.isCancelled());
                }
                clock.advanceByMillis(GRACE_PERIOD_MS + 5_000L);
                assertEquals(0, scheduler.runDueTasks(clock));
                verify(lifecycle, never()).removeDisconnectedPlayer(eq(roomId), any(), any(), anyLong());
                verify(rateLimit, never()).cleanUpWs("Alice:" + roomId);

                // Re-disconnecting origSession is a no-op; disconnecting newSession schedules cleanup
                int tasksBefore = scheduler.scheduledTasks().size();
                listener.handleWebSocketDisconnectListener(disconnectEvent(origSession, principal));
                assertEquals(tasksBefore, scheduler.scheduledTasks().size());
                assertEquals(0, scheduler.pendingCount());

                listener.handleWebSocketDisconnectListener(disconnectEvent(newSession, principal));
                assertEquals(1, scheduler.pendingCount());
            }
        }
    }

    @Test
    void roomDestructionCleansUpAllSessionsAndRegistrations() {
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
        listener.handleWebSocketDisconnectListener(disconnectEvent("alice-s1", alicePrincipal));
        assertEquals(1, scheduler.pendingCount());
        ScheduledTask aliceGrace = scheduler.scheduledTasks().getFirst();

        listener.registerSession(bobPrincipal, "bob-s1");

        listener.publishRoomDestroyed(roomId);
        // Step slightly past wall-clock now to run the immediate destruction task without reaching Alice's 60s grace deadline.
        clock.setMillis(System.currentTimeMillis() + 1_000L);
        assertEquals(1, scheduler.runDueTasks(clock));

        verify(lifecycle).cancelTimers(roomId, false);
        assertTrue(aliceGrace.isCancelled());
        verify(rateLimit).cleanUpWs("Host:" + roomId);
        verify(rateLimit).cleanUpWs("Alice:" + roomId);
        verify(rateLimit).cleanUpWs("Bob:" + roomId);

        clock.advanceByMillis(GRACE_PERIOD_MS + 5_000L);
        assertEquals(0, scheduler.runDueTasks(clock));
        verify(lifecycle, never()).removeDisconnectedPlayer(anyString(), anyString(), anyString(), anyLong());

        // Subsequent sessionId-only disconnects confirm sessionToPrincipal and active sessions were purged
        int scheduledCount = scheduler.scheduledTasks().size();
        for (String sid : new String[] { "host-s1", "host-s2", "alice-s1", "bob-s1" }) {
            listener.handleWebSocketDisconnectListener(disconnectEvent(sid, null));
        }
        assertEquals(scheduledCount, scheduler.scheduledTasks().size());
    }

    @Test
    void repeatedConnectionAndDestructionCyclesReleaseAllRegistrationsWithoutLeak() {
        int cycles = 50;
        for (int i = 0; i < cycles; i++) {
            scheduler.clear();
            String roomId = "churn-room-" + i;
            Room room = new Room(roomId, "Churn Room", "Host", 4, 10, 20, 1000, null);
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
            assertEquals(1, scheduler.runDueTasks(Instant.now().plusSeconds(1)));

            assertTrue(guestGrace.isCancelled());
            assertEquals(0, scheduler.pendingCount());
            verify(rateLimit).cleanUpWs("Host:" + roomId);
            verify(rateLimit).cleanUpWs("Guest:" + roomId);

            int tasksBefore = scheduler.scheduledTasks().size();
            listener.handleWebSocketDisconnectListener(disconnectEvent("host-sess-" + i, null));
            listener.handleWebSocketDisconnectListener(disconnectEvent("guest-sess-" + i, null));
            assertEquals(tasksBefore, scheduler.scheduledTasks().size());
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

        // Session mappings registered inside separate room leases drive subsequent principal-less disconnects
        listener.handleWebSocketDisconnectListener(disconnectEvent("session-a1", null));
        listener.handleWebSocketDisconnectListener(disconnectEvent("session-b1", null));
        assertEquals(2, scheduler.pendingCount());
    }

    @Test
    void cancelRoomPendingDisconnectsRequiresRoomLockAndCancelsOnlyMatchingRoom() {
        String roomA = "room-pending-A";
        String roomB = "room-pending-B";
        long deadline = clock.millis() + 30_000L;

        listener.scheduleRecoveredDisconnect(roomA, "Alice", deadline);
        listener.scheduleRecoveredDisconnect(roomB, "Bob", deadline);
        assertThat(scheduler.scheduledTasks()).hasSize(2);
        ScheduledTask taskA = scheduler.scheduledTasks().get(0);
        ScheduledTask taskB = scheduler.scheduledTasks().get(1);

        assertThatThrownBy(() -> listener.cancelRoomPendingDisconnects(roomA))
                .isInstanceOf(IllegalStateException.class);

        TestRoomLocks.withRoomLock(roomCoordinator, roomA, () -> {
            listener.cancelRoomPendingDisconnects(roomA);
            return null;
        });

        assertTrue(taskA.isCancelled());
        assertFalse(taskB.isCancelled());
        assertEquals(1, scheduler.pendingCount());

        Room bobRoom = new Room(roomB, "Room B", "Bob", 4, 10, 20, 1000, null);
        bobRoom.addPlayer("Bob");
        when(roomService.getRoom(roomB)).thenReturn(bobRoom);

        clock.advanceByMillis(30_000L);
        assertEquals(1, scheduler.runDueTasks(clock));

        verify(lifecycle, never()).removeDisconnectedPlayer(eq(roomA), anyString(), any(), anyLong());
        verify(lifecycle).removeDisconnectedPlayer(roomB, "Bob", null, deadline);
    }

    @Test
    void registerSessionDropsSocketsFromOlderMembership() {
        String roomId = "room-replace-mem";
        Room room = new Room(roomId, "Room", "Alice", 4, 10, 20, 1000, null);
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

        // Old socket was evicted on new membership connect, so disconnecting it is ignored.
        listener.handleWebSocketDisconnectListener(disconnectEvent("session-old", null));
        assertTrue(room.isPlayerConnected("Alice"));
        assertEquals(0, scheduler.pendingCount());

        // Disconnecting the replacement socket is therefore the last active session.
        listener.handleWebSocketDisconnectListener(
                disconnectEvent("session-new", new PlayerPrincipal("Alice", roomId, secondMembership)));
        assertFalse(room.isPlayerConnected("Alice"));
        assertEquals(1, scheduler.pendingCount());
    }

    @Test
    void subscribeAndUnsubscribeEventsHandleAuthenticatedAndAnonymousPrincipals() {
        PlayerPrincipal principal = new PlayerPrincipal("Alice", "room-sub", "mem-1");

        StompHeaderAccessor authSub = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        authSub.setSessionId("s-sub");
        authSub.setUser(principal);
        authSub.setDestination("/room/room-sub");
        assertDoesNotThrow(() -> listener.handleSubscribe(
                new SessionSubscribeEvent(this, MessageBuilder.createMessage(new byte[0], authSub.getMessageHeaders()))));

        StompHeaderAccessor anonSub = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        anonSub.setSessionId("s-anon");
        anonSub.setDestination("/room/room-sub");
        assertDoesNotThrow(() -> listener.handleSubscribe(
                new SessionSubscribeEvent(this, MessageBuilder.createMessage(new byte[0], anonSub.getMessageHeaders()))));

        StompHeaderAccessor authUnsub = StompHeaderAccessor.create(StompCommand.UNSUBSCRIBE);
        authUnsub.setSessionId("s-sub");
        authUnsub.setUser(principal);
        authUnsub.setSubscriptionId("sub-1");
        assertDoesNotThrow(() -> listener.handleUnsubscribe(
                new SessionUnsubscribeEvent(this, MessageBuilder.createMessage(new byte[0], authUnsub.getMessageHeaders()))));

        StompHeaderAccessor anonUnsub = StompHeaderAccessor.create(StompCommand.UNSUBSCRIBE);
        anonUnsub.setSessionId("s-anon");
        anonUnsub.setSubscriptionId("sub-2");
        assertDoesNotThrow(() -> listener.handleUnsubscribe(
                new SessionUnsubscribeEvent(this, MessageBuilder.createMessage(new byte[0], anonUnsub.getMessageHeaders()))));

        assertThat(scheduler.scheduledTasks()).isEmpty();
    }

    @Test
    void eventListenerAdaptersStartInitialGraceEndMembershipAndDestroyRoom() {
        String roomId = "room-events";
        Room room = new Room(roomId, "Events Room", "Alice", 4, 10, 20, 1000, null);
        String aliceMem = room.addPlayer("Alice");
        String bobMem = room.addPlayer("Bob");
        when(roomService.getRoom(roomId)).thenReturn(room);

        long deadline = clock.millis() + GRACE_PERIOD_MS;
        listener.onRoomMemberRegistered(new RoomMemberRegistered(roomId, "Alice", aliceMem, deadline));
        assertEquals(1, scheduler.pendingCount());

        clock.advanceByMillis(GRACE_PERIOD_MS);
        assertEquals(1, scheduler.runDueTasks(clock));
        verify(rateLimit).cleanUpWs("Alice:" + roomId);
        verify(lifecycle).removeDisconnectedPlayer(roomId, "Alice", aliceMem, deadline);

        listener.registerSession(new PlayerPrincipal("Bob", roomId, bobMem), "bob-sess");
        TestRoomLocks.withRoomLock(roomCoordinator, roomId, () -> {
            room.removePlayer("Bob");
            return null;
        });
        listener.onRoomMembershipEnded(new RoomMembershipEnded(roomId, "Bob", bobMem));
        assertEquals(1, scheduler.runAllPending());
        verify(rateLimit).cleanUpWs("Bob:" + roomId);

        listener.registerSession(new PlayerPrincipal("Alice", roomId, aliceMem), "alice-sess");
        listener.onRoomDestroyed(new RoomDestroyed(roomId));
        assertEquals(1, scheduler.runAllPending());
        verify(lifecycle).cancelTimers(roomId, false);
        verify(rateLimit, times(2)).cleanUpWs("Alice:" + roomId);
    }

    @Test
    void scheduleCleanupDropsTaskWhenSchedulerRejects() {
        scheduler.setRejectNext(true);
        assertDoesNotThrow(() -> listener.onRoomDestroyed(new RoomDestroyed("room-reject-1")));

        scheduler.setRejectNext(true);
        assertDoesNotThrow(() -> listener.onRoomMembershipEnded(
                new RoomMembershipEnded("room-reject-2", "Alice", "mem-1")));

        assertEquals(0, scheduler.pendingCount());
    }

    @Test
    void lateCancelRaceBeforeFutureAttachCancelsReturnedFutureAndPreventsEviction() {
        String roomId = "room-late-cancel";
        Room room = new Room(roomId, "Late Cancel Room", "Alice", 4, 10, 20, 1000, null);
        String membershipId = room.addPlayer("Alice");
        when(roomService.getRoom(roomId)).thenReturn(room);

        PlayerPrincipal principal = new PlayerPrincipal("Alice", roomId, membershipId);
        listener.registerSession(principal, "session-1");

        // Reconnect wins between taskScheduler.schedule(...) and PendingDisconnect.attach(future).
        scheduler.onNextSchedule(() -> listener.registerSession(principal, "session-2"));

        listener.handleWebSocketDisconnectListener(disconnectEvent("session-1", principal));

        assertThat(scheduler.scheduledTasks()).hasSize(1);
        assertTrue(scheduler.scheduledTasks().getFirst().isCancelled(),
                "Returned future must be cancelled by PendingDisconnect.attach");
        assertEquals(0, scheduler.pendingCount());

        clock.advanceByMillis(GRACE_PERIOD_MS + 5_000L);
        assertEquals(0, scheduler.runDueTasks(clock));
        verify(lifecycle, never()).removeDisconnectedPlayer(anyString(), anyString(), any(), anyLong());
    }

    @Test
    void graceExpiryRemovesDisconnectedPlayerWithMembershipAndDeadline() {
        String roomId = "room-grace-expiry";
        Room room = new Room(roomId, "Grace Room", "Alice", 4, 10, 20, 1000, null);
        String membershipId = room.addPlayer("Alice");
        when(roomService.getRoom(roomId)).thenReturn(room);
        when(lifecycle.gameExists(roomId)).thenReturn(true);
        when(lifecycle.playerExistsInGame(roomId, "Alice")).thenReturn(true);

        PlayerPrincipal principal = new PlayerPrincipal("Alice", roomId, membershipId);
        listener.registerSession(principal, "session-1");
        listener.handleWebSocketDisconnectListener(disconnectEvent("session-1", principal));

        long deadline = TestRoomLocks.withRoomLock(roomCoordinator, roomId,
                () -> room.getAbsenceDeadline("Alice"));

        clock.advanceByMillis(GRACE_PERIOD_MS - 5_000L);
        assertEquals(0, scheduler.runDueTasks(clock));
        verify(lifecycle, never()).removeDisconnectedPlayer(anyString(), anyString(), any(), anyLong());

        clock.advanceByMillis(10_000L);
        assertEquals(1, scheduler.runDueTasks(clock));
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
