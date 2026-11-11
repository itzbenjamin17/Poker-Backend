package com.pokergame.service;

import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.dto.response.RoomDataResponse;
import com.pokergame.exception.BadRequestException;
import com.pokergame.exception.ResourceNotFoundException;
import com.pokergame.model.Room;
import com.pokergame.util.LockLease;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.pokergame.util.ConcurrentTestSupport.stopExecutor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Concurrency and integration tests for Ticket #19:
 * Coordinate room admission, lookup, and expiration under RoomCoordinator.
 */
class RoomAdmissionCoordinationIntegrationTest {

    private RoomCoordinator roomCoordinator;
    private RoomService roomService;
    private SimpMessagingTemplate messagingTemplate;
    private ApplicationEventPublisher eventPublisher;
    private MutableClock clock;
    private ExecutorService executor;

    private static class MutableClock extends Clock {
        private long now = 1_900_000_000_000L;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(now);
        }

        @Override
        public long millis() {
            return now;
        }

        public void advance(long millis) {
            this.now += millis;
        }
    }

    @BeforeEach
    void setUp() {
        roomCoordinator = new RoomCoordinator();
        messagingTemplate = mock(SimpMessagingTemplate.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        clock = new MutableClock();
        executor = Executors.newCachedThreadPool();

        roomService = new RoomService(
                messagingTemplate,
                eventPublisher,
                120_000L,
                new BCryptPasswordEncoder(),
                roomCoordinator
        );
        roomService.setClock(clock);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (executor != null) {
            stopExecutor(executor);
        }
    }

    @Test
    @DisplayName("concurrent same-name room creation: exactly one succeeds and all other contenders are rejected")
    void concurrentSameNameRoomCreation_oneSucceedsOtherRejected() throws Exception {
        int threads = 16;
        String roomName = "ContendedSameNameRoom";
        CyclicBarrier barrier = new CyclicBarrier(threads);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger rejectedCount = new AtomicInteger(0);
        AtomicReference<String> createdRoomId = new AtomicReference<>();
        Queue<Throwable> unexpectedErrors = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < threads; i++) {
            final String hostName = "Host_" + i;
            executor.submit(() -> {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                    String roomId = roomService.createRoom(new CreateRoomRequest(
                            roomName, hostName, 6, 5, 10, 100, null));
                    successCount.incrementAndGet();
                    createdRoomId.set(roomId);
                } catch (BadRequestException e) {
                    if (e.getMessage() != null && e.getMessage().contains("already taken")) {
                        rejectedCount.incrementAndGet();
                    } else {
                        unexpectedErrors.add(e);
                    }
                } catch (Throwable t) {
                    unexpectedErrors.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        assertThat(doneLatch.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(unexpectedErrors).isEmpty();
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(rejectedCount.get()).isEqualTo(threads - 1);

        String winnerRoomId = createdRoomId.get();
        assertThat(winnerRoomId).isNotNull();
        Room room = roomService.getRoom(winnerRoomId);
        assertThat(room).isNotNull();
        assertThat(room.getRoomName()).isEqualTo(roomName);
        assertThat(roomService.getRooms()).hasSize(1);
    }

    @Test
    @DisplayName("competing admissions up to capacity under contention: exactly fills remaining capacity")
    void competingAdmissionsUpToCapacityUnderContention() throws Exception {
        int maxCapacity = 4; // 1 host + 3 joiners
        String roomName = "CapacityContentionRoom";
        String roomId = roomService.createRoom(new CreateRoomRequest(
                roomName, "OriginalHost", maxCapacity, 5, 10, 100, null));

        int contenders = 24;
        CyclicBarrier barrier = new CyclicBarrier(contenders);
        CountDownLatch doneLatch = new CountDownLatch(contenders);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger roomFullCount = new AtomicInteger(0);
        Queue<Throwable> unexpectedErrors = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < contenders; i++) {
            final String playerName = "Contender_" + i;
            executor.submit(() -> {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                    roomService.joinRoom(new JoinRoomRequest(roomName, playerName, null));
                    successCount.incrementAndGet();
                } catch (BadRequestException e) {
                    if ("Room is full".equals(e.getMessage())) {
                        roomFullCount.incrementAndGet();
                    } else {
                        unexpectedErrors.add(e);
                    }
                } catch (Throwable t) {
                    unexpectedErrors.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        assertThat(doneLatch.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(unexpectedErrors).isEmpty();
        assertThat(successCount.get()).isEqualTo(maxCapacity - 1);
        assertThat(roomFullCount.get()).isEqualTo(contenders - (maxCapacity - 1));

        Room room = roomService.getRoom(roomId);
        assertThat(room).isNotNull();
        assertThat(room.getPlayers()).hasSize(maxCapacity);
        assertThat(room.isFull()).isTrue();
    }

    @Test
    @DisplayName("pure lookup does not close room without explicit expiration coordination")
    void pureLookupDoesNotCloseExpiredRoomWithoutExplicitExpirationCoordination() {
        String roomName = "ExpiryLookupRoom";
        String roomId = roomService.createRoom(new CreateRoomRequest(
                roomName, "HostPlayer", 6, 5, 10, 100, null));

        Room roomBefore = roomService.getRoom(roomId);
        assertThat(roomBefore).isNotNull();
        Long deadline = roomBefore.getInactivityDeadlineEpochMs();
        assertThat(deadline).isNotNull();

        // Advance clock past the inactivity deadline
        clock.now = deadline + 10_000L;
        assertThat(roomService.isLobbyExpired(roomBefore)).isTrue();

        // Pure lookup getRoom(roomId) must return the room and NOT close it
        Room roomLookedUp = roomService.getRoom(roomId);
        assertThat(roomLookedUp).isNotNull();
        assertThat(roomLookedUp.getRoomId()).isEqualTo(roomId);
        assertThat(roomLookedUp.isTerminal()).isFalse();

        // Global enumeration also finds it because pure lookup didn't trigger destruction
        assertThat(roomService.getRooms()).contains(roomLookedUp);
        assertThat(roomService.findRoomByName(roomName)).isNotNull();

        // Now attempt to join the expired room: must be rejected with BadRequestException
        assertThatThrownBy(() -> roomService.joinRoom(new JoinRoomRequest(roomName, "LateJoiner", null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("expired due to inactivity");

        // The join triggered coordinated expiration check, so the room is now closed
        assertThat(roomService.getRoom(roomId)).isNull();
        assertThat(roomService.findRoomByName(roomName)).isNull();
        assertThat(roomService.getRooms()).doesNotContain(roomLookedUp);
    }

    @Test
    @DisplayName("explicit checkAndCloseIfExpired coordinates under RoomCoordinator and closes expired lobby")
    void explicitExpirationCoordinatesUnderRoomCoordinatorAndClosesExpiredLobby() {
        String roomName = "ExplicitCloseRoom";
        String roomId = roomService.createRoom(new CreateRoomRequest(
                roomName, "HostPlayer", 6, 5, 10, 100, null));

        Room room = roomService.getRoom(roomId);
        assertThat(room).isNotNull();
        Long deadline = room.getInactivityDeadlineEpochMs();
        assertThat(deadline).isNotNull();

        // Before expiry, checkAndCloseIfExpired returns false and does not destroy room
        boolean closedEarly = roomService.checkAndCloseIfExpired(roomId);
        assertThat(closedEarly).isFalse();
        assertThat(roomService.getRoom(roomId)).isNotNull();

        // Advance past deadline
        clock.now = deadline + 5_000L;

        // Explicit expiration coordinated under RoomCoordinator
        boolean closed = roomService.checkAndCloseIfExpired(roomId);
        assertThat(closed).isTrue();

        // Room is evicted and destroyed
        assertThat(roomService.getRoom(roomId)).isNull();
        assertThat(roomService.getCurrentHost(roomId)).isNull();
        assertThat(roomService.getRooms()).isEmpty();
    }

    @Test
    @DisplayName("cleanup without stale index entries: releases registrations and enables name reuse")
    void cleanupWithoutStaleIndexEntriesAllowsNameRecreationAndReleasesLocks() {
        String roomName = "RecyclableRoom";
        String roomId1 = roomService.createRoom(new CreateRoomRequest(
                roomName, "Host1", 4, 5, 10, 100, null));

        roomService.joinRoom(new JoinRoomRequest(roomName, "Guest1", null));
        assertThat(roomService.getRooms()).hasSize(1);
        assertThat(roomService.getCurrentHost(roomId1)).isEqualTo("Host1");

        // Destroy room
        roomService.destroyRoom(roomId1);

        // Indices must be completely cleaned up
        assertThat(roomService.getRoom(roomId1)).isNull();
        assertThat(roomService.getCurrentHost(roomId1)).isNull();
        assertThat(roomService.getRooms()).isEmpty();
        assertThat(roomService.findRoomByName(roomName)).isNull();
        assertThat(roomCoordinator.getLockRegistry().reservationCount(roomId1)).isEqualTo(0);

        // Name can now be reused for a new room creation without conflict
        String roomId2 = roomService.createRoom(new CreateRoomRequest(
                roomName, "Host2", 4, 5, 10, 100, null));
        assertThat(roomId2).isNotEqualTo(roomId1);
        assertThat(roomService.getRooms()).hasSize(1);
        assertThat(roomService.getCurrentHost(roomId2)).isEqualTo("Host2");

        // Clean up second room
        roomService.destroyRoom(roomId2);
        assertThat(roomCoordinator.getLockRegistry().reservationCount(roomId2)).isEqualTo(0);
        assertThat(roomCoordinator.getLockRegistry().activeKeyCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("room independence: deliberate lock on room A does not block admission or reads on room B")
    void roomIndependenceBlockedRoomADoesNotDelayRoomB() throws Exception {
        String roomA = "IndependenceRoomA";
        String roomB = "IndependenceRoomB";

        String idA = roomService.createRoom(new CreateRoomRequest(roomA, "HostA", 4, 5, 10, 100, null));
        String idB = roomService.createRoom(new CreateRoomRequest(roomB, "HostB", 4, 5, 10, 100, null));

        CountDownLatch roomAHeldLatch = new CountDownLatch(1);
        CountDownLatch roomAReleaseLatch = new CountDownLatch(1);
        AtomicBoolean roomABlockedWorkDone = new AtomicBoolean(false);

        // Task A acquires coordination lease on room A and holds it
        Future<Void> taskA = executor.submit(() -> {
            roomCoordinator.coordinate(idA, () -> {
                roomAHeldLatch.countDown();
                try {
                    boolean released = roomAReleaseLatch.await(5, TimeUnit.SECONDS);
                    assertThat(released).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            });
            roomABlockedWorkDone.set(true);
            return null;
        });

        // Ensure task A is actively coordinating room A
        assertThat(roomAHeldLatch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(roomABlockedWorkDone.get()).isFalse();

        // While room A is locked, perform operations on room B: join, getRoomData, pure lookup
        Future<RoomDataResponse> taskB = executor.submit(() -> {
            roomService.joinRoom(new JoinRoomRequest(roomB, "GuestB", null));
            return roomService.getRoomData(idB);
        });

        // Room B operations must complete promptly within bounded time without waiting for room A
        RoomDataResponse dataB = taskB.get(2, TimeUnit.SECONDS);
        assertThat(dataB).isNotNull();
        assertThat(dataB.players()).hasSize(2);
        assertThat(roomABlockedWorkDone.get()).as("Room A must still be blocked while Room B completes").isFalse();

        // Unblock Room A
        roomAReleaseLatch.countDown();
        taskA.get(2, TimeUnit.SECONDS);
        assertThat(roomABlockedWorkDone.get()).isTrue();
    }
}
