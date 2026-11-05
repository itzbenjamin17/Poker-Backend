package com.pokergame.service;

import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.exception.BadRequestException;
import com.pokergame.model.Room;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Queue;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Empirical Adversarial Concurrency Stress Suite for RoomService.joinRoom (Finding B1-5).
 */
@ExtendWith(MockitoExtension.class)
public class Tier4AdversarialRoomServiceChallengeTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Test
    @DisplayName("Challenge 1.1: High-contention join storm (60 threads vs capacity 3) strictly admits 2 joiners and rejects 58 with Room is full")
    void challenge_joinRoom_highContentionJoinStorm_strictlyAdmitsMaxPlayers() throws Exception {
        RoomService roomService = new RoomService(messagingTemplate);
        int maxCapacity = 3; // 1 host + 2 joiners allowed
        String roomId = roomService.createRoom(new CreateRoomRequest(
                "Contention Storm Room", "HostUser", maxCapacity, 5, 10, 100, null));

        int totalThreads = 60;
        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch readyLatch = new CountDownLatch(totalThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalThreads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger roomFullCount = new AtomicInteger(0);
        Queue<Throwable> unexpectedErrors = new ConcurrentLinkedQueue<>();

        try {
            for (int i = 1; i <= totalThreads; i++) {
                final int index = i;
                final String playerName = "Contender_" + index;
                executor.submit(() -> {
                    readyLatch.countDown();
                    try {
                        startLatch.await();
                        // Interleave joinRoom overloads (by request vs by roomId)
                        if (index % 2 == 0) {
                            roomService.joinRoom(new JoinRoomRequest("Contention Storm Room", playerName, null));
                        } else {
                            roomService.joinRoom(roomId, playerName, null);
                        }
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

            assertTrue(readyLatch.await(5, TimeUnit.SECONDS), "Threads failed to get ready in time");
            startLatch.countDown();
            assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "Concurrent joins timed out");
        } finally {
            // Shut the pool down even if an assertion above fails, so no worker thread leaks.
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "Executor must terminate promptly");
        }

        assertThat(unexpectedErrors).as("Unexpected errors encountered").isEmpty();
        assertEquals(maxCapacity - 1, successCount.get(), "Must admit exactly maxCapacity - 1 joiners");
        assertEquals(totalThreads - (maxCapacity - 1), roomFullCount.get(), "Excess threads must get 'Room is full'");

        Room room = roomService.getRoom(roomId);
        assertNotNull(room);
        assertEquals(maxCapacity, room.getPlayers().size(), "Room must have exactly maxCapacity players");
        assertTrue(room.isFull(), "Room must report isFull() == true");
    }

    @Test
    @DisplayName("Challenge 1.2: Concurrent join storm with duplicate names and unique names preserves capacity invariant")
    void challenge_joinRoom_mixedCollisionsAndJoins_enforcesExactCapacityAndErrorTypes() throws Exception {
        RoomService roomService = new RoomService(messagingTemplate);
        int maxCapacity = 4; // 1 host + 3 joiners allowed
        String roomId = roomService.createRoom(new CreateRoomRequest(
                "Collision Storm Room", "HostUser", maxCapacity, 5, 10, 100, null));

        int totalThreads = 50;
        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch readyLatch = new CountDownLatch(totalThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalThreads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger duplicateSuccessCount = new AtomicInteger(0);
        AtomicInteger roomFullCount = new AtomicInteger(0);
        AtomicInteger nameTakenCount = new AtomicInteger(0);
        Queue<Throwable> unexpectedErrors = new ConcurrentLinkedQueue<>();

        try {
            for (int i = 1; i <= totalThreads; i++) {
                final int index = i;
                final boolean isDuplicateCandidate = (index % 2 == 0);
                final String playerName = isDuplicateCandidate ? "ClashPlayer" : "UniquePlayer_" + index;

                executor.submit(() -> {
                    readyLatch.countDown();
                    try {
                        startLatch.await();
                        roomService.joinRoom(new JoinRoomRequest("Collision Storm Room", playerName, null));
                        successCount.incrementAndGet();
                        if (isDuplicateCandidate) {
                            duplicateSuccessCount.incrementAndGet();
                        }
                    } catch (BadRequestException e) {
                        if ("Room is full".equals(e.getMessage())) {
                            roomFullCount.incrementAndGet();
                        } else if ("Player name already taken".equals(e.getMessage())) {
                            nameTakenCount.incrementAndGet();
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

            assertTrue(readyLatch.await(5, TimeUnit.SECONDS), "Threads failed to get ready in time");
            startLatch.countDown();
            assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "Concurrent joins timed out");
        } finally {
            // Shut the pool down even if an assertion above fails, so no worker thread leaks.
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "Executor must terminate promptly");
        }

        assertThat(unexpectedErrors).as("Unexpected errors encountered").isEmpty();
        assertEquals(maxCapacity - 1, successCount.get(), "Exactly maxCapacity - 1 joins must succeed");
        assertTrue(duplicateSuccessCount.get() <= 1, "Duplicate player name can succeed at most once");
        assertEquals(totalThreads - (maxCapacity - 1), roomFullCount.get() + nameTakenCount.get(),
                "All failed attempts must be either 'Room is full' or 'Player name already taken'");

        Room room = roomService.getRoom(roomId);
        assertNotNull(room);
        assertEquals(maxCapacity, room.getPlayers().size());
        assertTrue(room.isFull());
    }

    @Test
    @DisplayName("Challenge 1.3: Rapid multi-cycle capacity saturation across varying room sizes (2 to 6)")
    void challenge_joinRoom_multiCycleRapidCapacitySaturation() throws Exception {
        RoomService roomService = new RoomService(messagingTemplate);
        Random random = new Random(42);

        for (int cycle = 1; cycle <= 20; cycle++) {
            int capacity = 2 + random.nextInt(5); // 2 to 6
            String roomName = "RapidRoom_" + cycle;
            String roomId = roomService.createRoom(new CreateRoomRequest(
                    roomName, "Host_" + cycle, capacity, 5, 10, 100, null));

            int joiners = 25;
            ExecutorService executor = Executors.newFixedThreadPool(joiners);
            CountDownLatch readyLatch = new CountDownLatch(joiners);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(joiners);

            AtomicInteger success = new AtomicInteger(0);
            AtomicInteger full = new AtomicInteger(0);
            Queue<Throwable> errors = new ConcurrentLinkedQueue<>();

            try {
                for (int j = 1; j <= joiners; j++) {
                    final String pName = "Cycler_" + cycle + "_" + j;
                    executor.submit(() -> {
                        readyLatch.countDown();
                        try {
                            startLatch.await();
                            roomService.joinRoom(roomId, pName, null);
                            success.incrementAndGet();
                        } catch (BadRequestException e) {
                            if ("Room is full".equals(e.getMessage())) {
                                full.incrementAndGet();
                            } else {
                                errors.add(e);
                            }
                        } catch (Throwable t) {
                            errors.add(t);
                        } finally {
                            doneLatch.countDown();
                        }
                    });
                }

                assertTrue(readyLatch.await(5, TimeUnit.SECONDS));
                startLatch.countDown();
                assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
            } finally {
                // Shut the pool down even if an assertion above fails, so no worker thread leaks.
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "Executor must terminate promptly");
            }

            assertThat(errors).isEmpty();
            assertEquals(capacity - 1, success.get(), "Cycle " + cycle + " must admit exactly capacity - 1");
            assertEquals(joiners - (capacity - 1), full.get(), "Cycle " + cycle + " excess joins must get Room is full");

            Room room = roomService.getRoom(roomId);
            assertEquals(capacity, room.getPlayers().size());
            assertTrue(room.isFull());

            roomService.destroyRoom(roomId);
        }
    }
}
