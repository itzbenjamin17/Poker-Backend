package com.pokergame.persistence;

import tools.jackson.databind.json.JsonMapper;
import com.pokergame.model.Game;
import com.pokergame.model.Player;
import com.pokergame.model.Room;
import com.pokergame.service.HandEvaluatorService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Tests aggregate snapshot mapper behavior. */
class AggregateSnapshotMapperTest {

    /**
     * Protects the contract that active game round trip preserves exact next cards and authoritative state.
     */
    @Test
    void activeGameRoundTripPreservesExactNextCardsAndAuthoritativeState() {
        HandEvaluatorService evaluator = new HandEvaluatorService();
        Room room = new Room("room-1", "Night Game", "Alice", 6, 5, 10, 1000, "secret");
        room.addPlayer("Alice");
        room.addPlayer("Bob");
        room.setGameStarted(true);
        Game game = new Game("room-1", List.of(
                new Player("Alice", "alice-id", 1000),
                new Player("Bob", "bob-id", 1000)), 5, 10, evaluator);
        assertFalse(game.resetForNewHand());
        game.dealHoleCards();
        game.postBlinds();

        AggregateSnapshotMapper mapper = new AggregateSnapshotMapper(
                JsonMapper.builder().findAndAddModules().build(), evaluator);
        RecoveredAggregate recovered = mapper.deserialize(mapper.serialize(room, "Bob", game));

        assertEquals(room.getRoomName(), recovered.room().getRoomName());
        assertEquals("Bob", recovered.currentHost());
        assertTrue(recovered.room().checkPassword("secret"));
        assertEquals(game.getPot(), recovered.game().getPot());
        assertEquals(game.getCurrentPlayer().getPlayerId(), recovered.game().getCurrentPlayer().getPlayerId());
        assertEquals(game.getPlayers().getFirst().getHoleCards(),
                recovered.game().getPlayers().getFirst().getHoleCards());

        game.dealFlop();
        recovered.game().dealFlop();
        assertEquals(game.getCommunityCards(), recovered.game().getCommunityCards());
    }
}
