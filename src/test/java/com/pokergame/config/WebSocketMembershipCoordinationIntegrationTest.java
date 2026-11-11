package com.pokergame.config;

import com.pokergame.model.Room;
import com.pokergame.security.PlayerPrincipal;
import com.pokergame.security.RateLimitService;
import com.pokergame.service.GameLifecycleService;
import com.pokergame.service.RoomCoordinator;
import com.pokergame.service.RoomService;
import com.pokergame.service.event.RoomDestroyed;
import com.pokergame.service.event.RoomMembershipRetired;
import com.pokergame.util.LockLease;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.pokergame.util.ConcurrentTestSupport.stopExecutor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Concurrency and deterministic integration tests for Ticket #21:
 * Coordinate WebSocket membership transitions under RoomCoordinator.
 * <p>
 * Verifies that:
 * 1. Reconnect vs disconnect races preserve current presence without corrupting state.
 * 2. Stale membership session events cannot evict or corrupt a replacement player.
 * 3. Session and registration cleanup on room destruction and session churn releases
 *    all transient registrations and lock keys without leaks.
 * 4. RoomCoordinator single-boundary serialization, reentrancy, and cross-room rejection
 *    are strictly enforced across WebSocket session operations.
 * </p>
 */
class WebSocketMembershipCoordinationIntegrationTest {

    private RoomCoordinator roomCoordinator;
    private RoomService roomService;
    private GameLifecycleService gameLifecycleService;
    private RateLimitService rateLimitService;
    private TaskScheduler taskScheduler;
    private WebSocketEventListener listener;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        roomCoordinator = new RoomCoordinator();
        roomService = mock(RoomService.class);
        gameLifecycleService = mock(GameLifecycleService.class);
        rateLimitService = mock(RateLimitService.class);
        taskScheduler = mock(TaskScheduler.class);
        executor = Executors.newCachedThreadPool();

        listener = new WebSocketEventListener(
                roomService,
                gameLifecycleService,
                rateLimitService,
                taskScheduler,
                roomCoordinator,
                60_000L
        );
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (executor != null) {
            stopExecutor(executor);
        }
    }

    private static Message<byte[]> stompMessage(StompCommand command, String sessionId, PlayerPrincipal principal) {
        StompHeaderAccessor headers = StompHeaderAccessor.create(command);
        headers.setSessionId(sessionId);
        headers.setUser(principal);
        return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    }

    // =========================================================================
    // 1. Reconnect vs Disconnect Races Preserve Current Presence
    // =========================================================================

    @Test
    @DisplayName("Reconnect vs Disconnect race: Reconnect wins lock -> disconnect skips grace timer and preserves presence")
    void reconnectWinsLock_DisconnectSkipsGraceTimerAndPreservesPresence() {
        String roomId = "room-race-1";
        String playerName = "Alice";
        Room room = new Room(roomId, "Race Room", playerName, 4, 10, 20, 1000, null);
        String membershipId = room.addPlayer(playerName);
        room.setPlayerConnected(playerName, true);
        when(roomService.getRoom(roomId)).thenReturn(room);

        PlayerPrincipal principal = new PlayerPrincipal(playerName, roomId, membershipId);

        // Session 1 is initially active
        listener.registerSession(principal, "session-1");
        assertThat(listener.getActiveSessionCount("Alice:" + roomId)).isEqualTo(1);

        // Reconnect session 2 arrives first
        listener.registerSession(principal, "session-2");
        assertThat(listener.getActiveSessionCount("Alice:" + roomId)).isEqualTo(2);
        verify(gameLifecycleService, atLeastOnce()).markPlayerReconnected(roomId, playerName);

        // Now disconnect event for session 1 arrives
        listener.handleWebSocketDisconnectListener(new SessionDisconnectEvent(
                this, stompMessage(StompCommand.DISCONNECT, "session-1", principal), "session-1", null));

        // Presence is preserved: session-2 remains active, no grace timer is scheduled
        assertThat(listener.getActiveSessionCount("Alice:" + roomId)).isEqualTo(1);
        assertThat(listener.hasPendingDisconnect("Alice:" + roomId)).isFalse();
        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
        assertThat(room.isPlayerConnected(playerName)).isTrue();
    }

    @Test
    @DisplayName("Reconnect vs Disconnect race: Disconnect wins lock -> Reconnect cancels timer and prevents eviction")
    void disconnectWinsLock_ReconnectCancelsTimerAndPreventsEviction() {
        String roomId = "room-race-2";
        String playerName = "Alice";
        Room room = new Room(roomId, "Race Room", playerName, 4, 10, 20, 1000, null);
        String membershipId = room.addPlayer(playerName);
        room.setPlayerConnected(playerName, true);
        when(roomService.getRoom(roomId)).thenReturn(room);
        when(gameLifecycleService.gameExists(roomId)).thenReturn(true);
        when(gameLifecycleService.playerExistsInGame(roomId, playerName)).thenReturn(true);

        PlayerPrincipal principal = new PlayerPrincipal(playerName, roomId, membershipId);
        listener.registerSession(principal, "session-1");

        // Disconnect session 1: grace timer scheduled
        ScheduledFuture<?> scheduledFuture = mock(ScheduledFuture.class);
        ArgumentCaptor<Runnable> cleanupCaptor = ArgumentCaptor.forClass(Runnable.class);
        doReturn(scheduledFuture).when(taskScheduler).schedule(cleanupCaptor.capture(), any(Instant.class));

        listener.handleWebSocketDisconnectListener(new SessionDisconnectEvent(
                this, stompMessage(StompCommand.DISCONNECT, "session-1", principal), "session-1", null));

        assertThat(listener.getActiveSessionCount("Alice:" + roomId)).isEqualTo(0);
        assertThat(listener.hasPendingDisconnect("Alice:" + roomId)).isTrue();
        assertThat(room.isPlayerConnected(playerName)).isFalse();
        Runnable graceCleanupCallback = cleanupCaptor.getValue();
        assertThat(graceCleanupCallback).isNotNull();

        // Reconnect session 2 arrives before grace expiry
        listener.registerSession(principal, "session-2");

        // Grace timer must be cancelled and active session registered
        assertThat(listener.getActiveSessionCount("Alice:" + roomId)).isEqualTo(1);
        assertThat(listener.hasPendingDisconnect("Alice:" + roomId)).isFalse();
        verify(scheduledFuture).cancel(false);
        verify(gameLifecycleService, atLeastOnce()).markPlayerReconnected(roomId, playerName);

        // Now suppose the scheduled callback fires late (or was already in flight)
        graceCleanupCallback.run();

        // Eviction must NOT occur because presence was restored
        verify(gameLifecycleService, never()).removeDisconnectedPlayer(any(), any(), any(), anyLong());
        assertThat(listener.getActiveSessionCount("Alice:" + roomId)).isEqualTo(1);
    }

    @Test
    @DisplayName("Concurrent Reconnect and Disconnect across multiple threads preserves presence without deadlock")
    void concurrentReconnectAndDisconnectPreservesPresenceWithoutDeadlock() throws Exception {
        int iterations = 40;
        for (int i = 0; i < iterations; i++) {
            final int index = i;
            String roomId = "room-concurrent-" + index;
            String playerName = "Alice";
            Room room = new Room(roomId, "Concurrent Room", playerName, 4, 10, 20, 1000, null);
            String membershipId = room.addPlayer(playerName);
            room.setPlayerConnected(playerName, true);
            when(roomService.getRoom(roomId)).thenReturn(room);

            PlayerPrincipal principal = new PlayerPrincipal(playerName, roomId, membershipId);

            // Start with session-orig
            listener.registerSession(principal, "session-orig-" + index);

            CyclicBarrier barrier = new CyclicBarrier(2);
            CountDownLatch done = new CountDownLatch(2);

            // Thread 1: disconnect session-orig
            executor.submit(() -> {
                try {
                    barrier.await(2, TimeUnit.SECONDS);
                    listener.handleWebSocketDisconnectListener(new SessionDisconnectEvent(
                            this, stompMessage(StompCommand.DISCONNECT, "session-orig-" + index, principal), "session-orig-" + index, null));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    done.countDown();
                }
            });

            // Thread 2: connect session-new
            executor.submit(() -> {
                try {
                    barrier.await(2, TimeUnit.SECONDS);
                    listener.registerSession(principal, "session-new-" + index);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    done.countDown();
                }
            });

            assertThat(done.await(3, TimeUnit.SECONDS)).isTrue();

            // After both threads finish:
            // Since session-new connected, session-new MUST be active.
            // If reconnect ran first, disconnect of session-orig didn't schedule grace.
            // If disconnect ran first, reconnect cancelled the grace timer.
            // Either way: Alice has active session session-new, and no pending disconnect!
            assertThat(listener.getActiveSessionCount("Alice:" + roomId))
                    .as("Active session count for iteration " + i)
                    .isEqualTo(1);
            assertThat(listener.hasPendingDisconnect("Alice:" + roomId))
                    .as("No pending disconnect for iteration " + i)
                    .isFalse();
        }
    }

    // =========================================================================
    // 2. Stale Membership Session Events Cannot Evict or Corrupt Replacement
    // =========================================================================

    @Test
    @DisplayName("Stale membership disconnect cannot mark current replacement player disconnected or schedule timer")
    void staleMembershipDisconnectCannotMarkCurrentPlayerDisconnected() {
        String roomId = "room-stale-1";
        String playerName = "Alice";
        Room room = new Room(roomId, "Stale Room", playerName, 4, 10, 20, 1000, null);

        // Initial membership
        String initialMembership = room.addPlayer(playerName);
        room.setPlayerConnected(playerName, true);
        when(roomService.getRoom(roomId)).thenReturn(room);

        PlayerPrincipal oldPrincipal = new PlayerPrincipal(playerName, roomId, initialMembership);
        listener.registerSession(oldPrincipal, "session-old");

        // Player leaves and rejoins with replacement membership
        room.removePlayer(playerName);
        String replacementMembership = room.addPlayer(playerName);
        room.setPlayerConnected(playerName, true);
        assertThat(replacementMembership).isNotEqualTo(initialMembership);

        PlayerPrincipal replacementPrincipal = new PlayerPrincipal(playerName, roomId, replacementMembership);
        listener.registerSession(replacementPrincipal, "session-replacement");

        assertThat(listener.getActiveSessionCount("Alice:" + roomId)).isEqualTo(1);

        // Stale disconnect event arrives for old membership
        listener.handleWebSocketDisconnectListener(new SessionDisconnectEvent(
                this, stompMessage(StompCommand.DISCONNECT, "session-old", oldPrincipal), "session-old", null));

        // Current presence must remain intact
        assertThat(room.isPlayerConnected(playerName)).isTrue();
        assertThat(listener.getActiveSessionCount("Alice:" + roomId)).isEqualTo(1);
        assertThat(listener.hasPendingDisconnect("Alice:" + roomId)).isFalse();
        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
        verify(gameLifecycleService, never()).markPlayerDisconnected(any(), any(), anyLong());
    }

    @Test
    @DisplayName("Stale membership connect is rejected and does not register active session or corrupt replacement")
    void staleMembershipConnectIsRejectedWithoutCorruptingReplacement() {
        String roomId = "room-stale-2";
        String playerName = "Alice";
        Room room = new Room(roomId, "Stale Room 2", playerName, 4, 10, 20, 1000, null);

        // Active replacement membership
        String activeMembership = room.addPlayer(playerName);
        room.setPlayerConnected(playerName, true);
        when(roomService.getRoom(roomId)).thenReturn(room);

        PlayerPrincipal activePrincipal = new PlayerPrincipal(playerName, roomId, activeMembership);
        listener.registerSession(activePrincipal, "session-active");
        assertThat(listener.getActiveSessionCount("Alice:" + roomId)).isEqualTo(1);

        // Stale connect arrives with obsolete membership
        PlayerPrincipal stalePrincipal = new PlayerPrincipal(playerName, roomId, "obsolete-mem-xyz");
        listener.handleWebSocketConnectListener(new SessionConnectEvent(
                this, stompMessage(StompCommand.CONNECT, "session-stale", stalePrincipal)));

        // Session count remains 1 (stale connect ignored)
        assertThat(listener.getActiveSessionCount("Alice:" + roomId)).isEqualTo(1);
        assertThat(listener.getSessionToPrincipalCount()).isEqualTo(1);
        // markPlayerReconnected was not called for stale connect
        verify(gameLifecycleService, times(1)).markPlayerReconnected(roomId, playerName);
    }

    @Test
    @DisplayName("Delayed membership retirement event does not evict replacement player")
    void delayedMembershipRetirementDoesNotEvictReplacementPlayer() {
        String roomId = "room-stale-3";
        String playerName = "Alice";
        Room room = new Room(roomId, "Stale Room 3", playerName, 4, 10, 20, 1000, null);

        String firstMembership = room.addPlayer(playerName);
        when(roomService.getRoom(roomId)).thenReturn(room);

        PlayerPrincipal firstPrincipal = new PlayerPrincipal(playerName, roomId, firstMembership);
        listener.registerSession(firstPrincipal, "session-first");

        // Retirement published for first membership
        ArgumentCaptor<Runnable> retirementCaptor = ArgumentCaptor.forClass(Runnable.class);
        listener.publishMembershipRetired(roomId, playerName, firstMembership);
        verify(taskScheduler).schedule(retirementCaptor.capture(), any(Instant.class));

        // Before retirement task runs, Alice leaves and rejoins with second membership
        room.removePlayer(playerName);
        String secondMembership = room.addPlayer(playerName);
        assertThat(secondMembership).isNotEqualTo(firstMembership);

        PlayerPrincipal secondPrincipal = new PlayerPrincipal(playerName, roomId, secondMembership);
        listener.registerSession(secondPrincipal, "session-second");

        // Now delayed retirement runs
        retirementCaptor.getValue().run();

        // Replacement player must not be evicted
        assertThat(room.hasPlayer(playerName)).isTrue();
        assertThat(room.getMembershipId(playerName)).isEqualTo(secondMembership);
        assertThat(listener.getActiveSessionCount("Alice:" + roomId)).isEqualTo(1);
        assertThat(listener.getSessionToPrincipalCount()).isEqualTo(1);
    }

    // =========================================================================
    // 3. Session and Registration Cleanup on Room Destruction & Session Churn
    // =========================================================================

    @Test
    @DisplayName("Room destruction cleans up all active sessions, pending disconnects, and lock registrations")
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

        // Host has 2 sessions
        listener.registerSession(hostPrincipal, "host-s1");
        listener.registerSession(hostPrincipal, "host-s2");

        // Alice has 1 session, then disconnects -> pending disconnect timer
        listener.registerSession(alicePrincipal, "alice-s1");
        ScheduledFuture<?> aliceFuture = mock(ScheduledFuture.class);
        doReturn(aliceFuture).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));
        listener.handleWebSocketDisconnectListener(new SessionDisconnectEvent(
                this, stompMessage(StompCommand.DISCONNECT, "alice-s1", alicePrincipal), "alice-s1", null));

        // Bob has 1 session
        listener.registerSession(bobPrincipal, "bob-s1");

        assertThat(listener.getActiveSessionCount("Host:" + roomId)).isEqualTo(2);
        assertThat(listener.getActiveSessionCount("Alice:" + roomId)).isEqualTo(0);
        assertThat(listener.getActiveSessionCount("Bob:" + roomId)).isEqualTo(1);
        assertThat(listener.hasPendingDisconnect("Alice:" + roomId)).isTrue();
        assertThat(listener.getSessionToPrincipalCount()).isEqualTo(3); // host-s1, host-s2, bob-s1

        // Destroy room
        ArgumentCaptor<Runnable> destroyCaptor = ArgumentCaptor.forClass(Runnable.class);
        listener.publishRoomDestroyed(roomId);
        verify(taskScheduler, atLeastOnce()).schedule(destroyCaptor.capture(), any(Instant.class));

        // Run scheduled destruction cleanup
        destroyCaptor.getValue().run();

        // Verify full state teardown
        verify(gameLifecycleService).cancelRuntimeTasks(roomId, false);
        verify(aliceFuture).cancel(false);
        assertThat(listener.getActiveSessionCount("Host:" + roomId)).isEqualTo(0);
        assertThat(listener.getActiveSessionCount("Alice:" + roomId)).isEqualTo(0);
        assertThat(listener.getActiveSessionCount("Bob:" + roomId)).isEqualTo(0);
        assertThat(listener.hasPendingDisconnect("Alice:" + roomId)).isFalse();
        assertThat(listener.getSessionToPrincipalCount()).isEqualTo(0);
        assertThat(listener.getPendingDisconnectCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Repeated connection and destruction cycles release all lock keys and registrations without leak")
    void repeatedConnectionAndDestructionCyclesReleaseAllRegistrationsWithoutLeak() {
        int cycles = 50;
        for (int i = 0; i < cycles; i++) {
            String roomId = "churn-room-" + i;
            Room room = new Room(roomId, "Churn Room", "Host", 4, 10, 20, 1000, null);
            String hostMem = room.addPlayer("Host");
            String guestMem = room.addPlayer("Guest");
            when(roomService.getRoom(roomId)).thenReturn(room);

            PlayerPrincipal host = new PlayerPrincipal("Host", roomId, hostMem);
            PlayerPrincipal guest = new PlayerPrincipal("Guest", roomId, guestMem);

            // Connect sessions
            listener.registerSession(host, "host-sess-" + i);
            listener.registerSession(guest, "guest-sess-" + i);

            // Guest disconnects
            ScheduledFuture<?> future = mock(ScheduledFuture.class);
            doReturn(future).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));
            listener.handleWebSocketDisconnectListener(new SessionDisconnectEvent(
                    this, stompMessage(StompCommand.DISCONNECT, "guest-sess-" + i, guest), "guest-sess-" + i, null));

            // Destroy room
            ArgumentCaptor<Runnable> cleanupCaptor = ArgumentCaptor.forClass(Runnable.class);
            listener.publishRoomDestroyed(roomId);
            verify(taskScheduler, atLeastOnce()).schedule(cleanupCaptor.capture(), any(Instant.class));
            cleanupCaptor.getValue().run();

            // After each cycle, all resources for the destroyed room are cleared
            assertThat(listener.getActiveSessionCount("Host:" + roomId)).isEqualTo(0);
            assertThat(listener.getActiveSessionCount("Guest:" + roomId)).isEqualTo(0);
            assertThat(listener.hasPendingDisconnect("Guest:" + roomId)).isFalse();
        }

        // Global counters must be completely clean
        assertThat(listener.getSessionToPrincipalCount()).isEqualTo(0);
        assertThat(listener.getPendingDisconnectCount()).isEqualTo(0);
    }

    // =========================================================================
    // 4. RoomCoordinator Boundary, Reentrancy, and Cross-Room Isolation
    // =========================================================================

    @Test
    @DisplayName("Same-room reentrancy allows nested coordination while cross-room coordination is rejected")
    void roomCoordinatorReentrancyAndCrossRoomRejection() {
        String roomA = "room-iso-A";
        String roomB = "room-iso-B";

        PlayerPrincipal principalA = new PlayerPrincipal("Alice", roomA);
        PlayerPrincipal principalB = new PlayerPrincipal("Alice", roomB);

        // Coordinate room A
        roomCoordinator.coordinate(roomA, () -> {
            assertThat(roomCoordinator.isHeldByCurrentThread(roomA)).isTrue();
            assertThat(roomCoordinator.isHeldByCurrentThread(roomB)).isFalse();

            // Reentrancy on room A succeeds
            listener.registerSession(principalA, "session-a1");
            assertThat(listener.getActiveSessionCount("Alice:" + roomA)).isEqualTo(1);

            // Attempting to coordinate room B while holding room A fails fast
            assertThatThrownBy(() -> listener.registerSession(principalB, "session-b1"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot coordinate room " + roomB);
        });

        // After releasing room A, room B can be coordinated independently
        roomCoordinator.coordinate(roomB, () -> {
            assertThat(roomCoordinator.isHeldByCurrentThread(roomB)).isTrue();
            listener.registerSession(principalB, "session-b1");
            assertThat(listener.getActiveSessionCount("Alice:" + roomB)).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("cancelScheduledDisconnect cancels and removes pending disconnect with membership guard")
    void cancelScheduledDisconnectWithMembershipGuard() {
        String roomId = "room-cancel-1";
        String playerName = "Alice";
        Room room = new Room(roomId, "Cancel Room", playerName, 4, 10, 20, 1000, null);
        String memId = room.addPlayer(playerName);
        room.setPlayerConnected(playerName, true);
        when(roomService.getRoom(roomId)).thenReturn(room);

        PlayerPrincipal principal = new PlayerPrincipal(playerName, roomId, memId);
        listener.registerSession(principal, "session-c1");

        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        doReturn(future).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));

        // Disconnect
        listener.handleWebSocketDisconnectListener(new SessionDisconnectEvent(
                this, stompMessage(StompCommand.DISCONNECT, "session-c1", principal), "session-c1", null));

        assertThat(listener.hasPendingDisconnect("Alice:" + roomId)).isTrue();

        // Cancel with wrong membership ID returns false and preserves pending
        boolean cancelledWrong = listener.cancelScheduledDisconnect(roomId, playerName, "wrong-mem");
        assertThat(cancelledWrong).isFalse();
        assertThat(listener.hasPendingDisconnect("Alice:" + roomId)).isTrue();
        verify(future, never()).cancel(false);

        // Cancel with correct membership ID succeeds
        boolean cancelledRight = listener.cancelScheduledDisconnect(roomId, playerName, memId);
        assertThat(cancelledRight).isTrue();
        assertThat(listener.hasPendingDisconnect("Alice:" + roomId)).isFalse();
        verify(future).cancel(false);
    }
}
