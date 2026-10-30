package com.pokergame.persistence.snapshot;

import com.pokergame.enums.HandRank;
import com.pokergame.enums.Rank;
import com.pokergame.enums.Suit;
import com.pokergame.model.Card;
import com.pokergame.model.Game;
import com.pokergame.model.Player;
import com.pokergame.service.HandEvaluatorService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Empirical Adversarial Concurrency Stress Suite for AggregateSnapshotMapper.toState(Game) (Finding B6-2).
 */
public class Tier4AdversarialSnapshotChallengeTest {

    @Test
    @DisplayName("Challenge 2.1: Continuous mutator vs reader high-contention stress on AggregateSnapshotMapper.toState(Game)")
    void challenge_aggregateSnapshotMapper_concurrentMutationsVsSnapshot_noCME() throws Exception {
        HandEvaluatorService evaluator = new HandEvaluatorService();
        AggregateSnapshotMapper mapper = new AggregateSnapshotMapper(
                JsonMapper.builder().findAndAddModules().build(), evaluator);

        String roomId = "game-cme-challenge-room";
        Player p1 = new Player("Player1", "p1", 1000);
        Player p2 = new Player("Player2", "p2", 1000);
        Player p3 = new Player("Player3", "p3", 1000);
        Game game = new Game(roomId, new ArrayList<>(List.of(p1, p2, p3)), 10, 20, evaluator);

        int readerCount = 6;
        int mutatorCount = 4;
        int operationsPerMutator = 1500;

        AtomicBoolean running = new AtomicBoolean(true);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicInteger snapshotsTaken = new AtomicInteger();

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(mutatorCount);
        ExecutorService executor = Executors.newFixedThreadPool(readerCount + mutatorCount);

        // Readers continuously extract state snapshots
        for (int r = 0; r < readerCount; r++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    while (running.get() && failure.get() == null) {
                        AggregateStateImage.GameState state = mapper.toState(game);
                        if (state != null) {
                            snapshotsTaken.incrementAndGet();
                            assertNotNull(state.gameId());
                            assertNotNull(state.players());
                            assertTrue(state.players().size() >= 3);
                        }
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            });
        }

        // Mutator 1 & 2: Add and remove players to/from game lists under game monitor
        for (int m = 0; m < 2; m++) {
            final int mutatorId = m;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int op = 0; op < operationsPerMutator && running.get() && failure.get() == null; op++) {
                        Player temp = new Player("Temp_" + mutatorId + "_" + op, "tmp_" + mutatorId + "_" + op, 200);
                        synchronized (game) {
                            game.getPlayers().add(temp);
                            game.getActivePlayers().add(temp);
                            game.getPlayers().remove(temp);
                            game.getActivePlayers().remove(temp);
                        }
                        if (op % 100 == 0) {
                            Thread.yield();
                        }
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Mutator 3: Modify player attributes (chips, cards, status) under game monitor
        executor.submit(() -> {
            try {
                startLatch.await();
                for (int op = 0; op < operationsPerMutator && running.get() && failure.get() == null; op++) {
                    synchronized (game) {
                        p1.setDisconnected(op % 2 == 0);
                        p2.setReadyForNextHand(op % 2 != 0);
                        p1.setBestHand(List.of(new Card(Rank.ACE, Suit.SPADES), new Card(Rank.KING, Suit.HEARTS)));
                        p1.setHandRank(HandRank.HIGH_CARD);
                    }
                    if (op % 100 == 0) {
                        Thread.yield();
                    }
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            } finally {
                doneLatch.countDown();
            }
        });

        // Mutator 4: Modify community cards, pot, bets under game monitor
        executor.submit(() -> {
            try {
                startLatch.await();
                for (int op = 0; op < operationsPerMutator && running.get() && failure.get() == null; op++) {
                    synchronized (game) {
                        game.incrementStateRevision();
                    }
                    if (op % 100 == 0) {
                        Thread.yield();
                    }
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            } finally {
                doneLatch.countDown();
            }
        });

        startLatch.countDown();
        assertTrue(doneLatch.await(15, TimeUnit.SECONDS), "Mutators did not complete within timeout");
        running.set(false);
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Executor did not terminate in time");

        assertNull(failure.get(), "Failure occurred during concurrent snapshot extraction: " + failure.get());
        assertTrue(snapshotsTaken.get() >= 500, "Expected at least 500 snapshots taken, got: " + snapshotsTaken.get());
    }

    @Test
    @DisplayName("Challenge 2.2: Concurrent removePlayerFromGame and toState snapshot extraction")
    void challenge_aggregateSnapshotMapper_concurrentRemovePlayerAndSnapshot() throws Exception {
        HandEvaluatorService evaluator = new HandEvaluatorService();
        AggregateSnapshotMapper mapper = new AggregateSnapshotMapper(
                JsonMapper.builder().findAndAddModules().build(), evaluator);

        String roomId = "cross-cutting-remove-room";
        Player p1 = new Player("Alice", "p-1", 1000);
        Player p2 = new Player("Bob", "p-2", 1000);
        Player p3 = new Player("Charlie", "p-3", 1000);
        Game game = new Game(roomId, new ArrayList<>(List.of(p1, p2, p3)), 10, 20, evaluator);

        int readerThreads = 4;
        int operations = 1200;
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicInteger snapshotCount = new AtomicInteger();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);

        ExecutorService executor = Executors.newFixedThreadPool(readerThreads + 2);

        // Snapshot readers
        for (int r = 0; r < readerThreads; r++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    while (running.get() && failure.get() == null) {
                        AggregateStateImage.GameState state = mapper.toState(game);
                        if (state != null) {
                            snapshotCount.incrementAndGet();
                            assertNotNull(state.players());
                            assertTrue(state.players().size() >= 3);
                        }
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            });
        }

        // Mutator A: repeatedly adding and removing temporary player via game.removePlayerFromGame
        executor.submit(() -> {
            try {
                startLatch.await();
                for (int i = 0; i < operations && running.get() && failure.get() == null; i++) {
                    Player temp = new Player("TempPlayer_" + i, "temp_" + i, 500);
                    synchronized (game) {
                        game.getPlayers().add(temp);
                        game.getActivePlayers().add(temp);
                        game.removePlayerFromGame(temp);
                    }
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            } finally {
                doneLatch.countDown();
            }
        });

        // Mutator B: toggle player properties and revision
        executor.submit(() -> {
            try {
                startLatch.await();
                for (int i = 0; i < operations && running.get() && failure.get() == null; i++) {
                    synchronized (game) {
                        p1.setDisconnected(i % 2 == 0);
                        game.incrementStateRevision();
                    }
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            } finally {
                doneLatch.countDown();
            }
        });

        startLatch.countDown();
        assertTrue(doneLatch.await(15, TimeUnit.SECONDS), "Mutators timed out");
        running.set(false);
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        assertNull(failure.get(), "Failure occurred: " + failure.get());
        assertTrue(snapshotCount.get() > 0, "Snapshots must have been captured");
    }
}
