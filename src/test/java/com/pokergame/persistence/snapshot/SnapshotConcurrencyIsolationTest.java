package com.pokergame.persistence.snapshot;

import com.pokergame.model.Game;
import com.pokergame.model.Player;
import com.pokergame.service.HandEvaluatorService;
import com.pokergame.service.RoomCoordinator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.pokergame.util.ConcurrentTestSupport.awaitBlockedOn;
import static com.pokergame.util.ConcurrentTestSupport.stopExecutor;
import static org.junit.jupiter.api.Assertions.*;

/** Tests consistency and isolation of recovery images captured during game mutations. */
class SnapshotConcurrencyIsolationTest {

    /** Queues a reader midway through a mutation and checks both sides of the snapshot boundary. */
    @Test
    void queuedSnapshotSeesCompleteMutationAndPriorSnapshotRemainsUnchanged() throws Exception {
        HandEvaluatorService evaluator = new HandEvaluatorService();
        AggregateSnapshotMapper mapper = new AggregateSnapshotMapper(
                JsonMapper.builder().findAndAddModules().build(), evaluator);
        Player alice = new Player("Alice", "p1", 1000);
        Player bob = new Player("Bob", "p2", 1000);
        Player departing = new Player("Charlie", "p3", 1000);
        Game game = new Game("snapshot-room", List.of(alice, bob, departing), 10, 20, evaluator);
        AggregateStateImage.GameState before = mapper.toState(game);
        RoomCoordinator coordinator = new RoomCoordinator();

        AtomicReference<Thread> reader = new AtomicReference<>();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<AggregateStateImage.GameState> capture;
            try (var ignored = coordinator.acquire("snapshot-room")) {
                alice.setDisconnected(true);
                capture = executor.submit(() -> {
                    reader.set(Thread.currentThread());
                    return coordinator.coordinate("snapshot-room", () -> mapper.toState(game));
                });
                awaitBlockedOn(reader, coordinator.getLockRegistry());

                bob.setReadyForNextHand(true);
                game.removePlayerFromGame(departing);
                game.incrementStateRevision();
            }

            AggregateStateImage.GameState after = capture.get(5, TimeUnit.SECONDS);
            assertEquals(before.stateRevision() + 1, after.stateRevision());
            assertEquals(List.of("p1", "p2"), after.activePlayerIds());
            assertEquals(after.activePlayerIds(),
                    after.players().stream().map(AggregateStateImage.PlayerState::playerId).toList());
            assertTrue(after.players().get(0).disconnected());
            assertTrue(after.players().get(1).readyForNextHand());

            assertEquals(List.of("p1", "p2", "p3"), before.activePlayerIds());
            assertEquals(before.activePlayerIds(),
                    before.players().stream().map(AggregateStateImage.PlayerState::playerId).toList());
            assertFalse(before.players().get(0).disconnected());
            assertFalse(before.players().get(1).readyForNextHand());
            assertThrows(UnsupportedOperationException.class, () -> after.players().clear());
        } finally {
            stopExecutor(executor);
        }
    }
}
