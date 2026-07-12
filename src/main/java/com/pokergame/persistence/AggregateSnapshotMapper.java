package com.pokergame.persistence;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.pokergame.model.Card;
import com.pokergame.model.Game;
import com.pokergame.model.Player;
import com.pokergame.model.Room;
import com.pokergame.service.HandEvaluatorService;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public final class AggregateSnapshotMapper {
    private final ObjectMapper objectMapper;
    private final HandEvaluatorService handEvaluator;

    public AggregateSnapshotMapper(ObjectMapper objectMapper, HandEvaluatorService handEvaluator) {
        this.objectMapper = objectMapper;
        this.handEvaluator = handEvaluator;
    }

    public byte[] serialize(Room room, String currentHost, Game game) {
        AggregateStateImage.RoomState roomState = new AggregateStateImage.RoomState(
                room.getRoomId(), room.getRoomName(), room.getHostName(), room.getMaxPlayers(), room.getSmallBlind(),
                room.getBigBlind(), room.getBuyIn(), room.getPasswordForPersistence(), room.getCreatedAt(),
                room.getPlayersWithJoinTimeSnapshot(), room.isGameStarted());
        AggregateStateImage.GameState gameState = game == null ? null : toState(game);
        return write(new AggregateStateImage(AggregateStateImage.CURRENT_SCHEMA_VERSION, false, roomState,
                currentHost, gameState));
    }

    public byte[] serializeDeletion(String roomId) {
        AggregateStateImage.RoomState tombstone = new AggregateStateImage.RoomState(
                roomId, "deleted", "deleted", 2, 1, 2, 20, null, null, java.util.Map.of(), false);
        return write(new AggregateStateImage(AggregateStateImage.CURRENT_SCHEMA_VERSION, true, tombstone,
                "deleted", null));
    }

    public RecoveredAggregate deserialize(byte[] bytes) {
        try {
            AggregateStateImage image = objectMapper.readValue(bytes, AggregateStateImage.class);
            if (image.schemaVersion() != AggregateStateImage.CURRENT_SCHEMA_VERSION) {
                throw new PersistenceException("Unsupported aggregate snapshot schema: " + image.schemaVersion());
            }
            if (image.deleted()) {
                return new RecoveredAggregate(null, null, null, true);
            }
            AggregateStateImage.RoomState state = image.room();
            Room room = Room.restore(state.roomId(), state.roomName(), state.originalHost(), state.maxPlayers(),
                    state.smallBlind(), state.bigBlind(), state.buyIn(), state.password(), state.createdAt(),
                    state.playersWithJoinTime(), state.gameStarted());
            Game game = image.game() == null ? null : restoreGame(image.game());
            return new RecoveredAggregate(room, image.currentHost(), game, false);
        } catch (JacksonException | IllegalArgumentException e) {
            throw new PersistenceException("Cannot decode aggregate snapshot", e);
        }
    }

    private byte[] write(AggregateStateImage image) {
        try {
            return objectMapper.writeValueAsBytes(image);
        } catch (JacksonException e) {
            throw new PersistenceException("Cannot encode aggregate snapshot", e);
        }
    }

    private AggregateStateImage.GameState toState(Game game) {
        return new AggregateStateImage.GameState(
                game.getGameId(), game.getPlayers().stream().map(this::toState).toList(),
                game.getActivePlayers().stream().map(Player::getPlayerId).toList(),
                cards(game.getRemainingDeckSnapshot()), cards(game.getCommunityCards()), game.getPot(),
                game.getDealerPosition(), game.getSmallBlindPosition(), game.getBigBlindPosition(),
                game.getCurrentPlayerPosition(), game.getCurrentHighestBet(), game.getCurrentPhase(),
                game.isGameOver(), game.getSmallBlind(), game.getBigBlind(), game.getHandContributionsSnapshot(),
                game.isReadyCountdownActive(), game.getReadyCountdownDeadlineEpochMs(),
                game.hasEveryoneHadInitialTurn(), game.getActedPlayerIdsSnapshot(),
                game.getScheduledTaskDeadlinesSnapshot());
    }

    private AggregateStateImage.PlayerState toState(Player player) {
        return new AggregateStateImage.PlayerState(
                player.getName(), player.getPlayerId(), cards(player.getHoleCards()), cards(player.getBestHand()),
                player.getHandRank(), player.getChips(), player.getCurrentBet(), player.getHasFolded(),
                player.getIsAllIn(), player.getIsOut(), player.getIsDisconnected(),
                player.getDisconnectDeadlineEpochMs(), player.getIsReadyForNextHand());
    }

    private Game restoreGame(AggregateStateImage.GameState state) {
        List<Player> players = state.players().stream().map(player -> Player.restore(
                player.name(), player.playerId(), cardsFromState(player.holeCards()),
                cardsFromState(player.bestHand()), player.handRank(), player.chips(), player.currentBet(),
                player.folded(), player.allIn(), player.out(), player.disconnected(),
                player.disconnectDeadlineEpochMs(), player.readyForNextHand())).toList();
        return Game.restore(state.gameId(), players, state.activePlayerIds(), cardsFromState(state.remainingDeck()),
                cardsFromState(state.communityCards()), state.pot(), state.dealerPosition(), state.smallBlindPosition(),
                state.bigBlindPosition(), state.currentPlayerPosition(), state.currentHighestBet(), state.currentPhase(),
                state.gameOver(), state.smallBlind(), state.bigBlind(), state.handContributions(),
                state.readyCountdownActive(), state.readyCountdownDeadlineEpochMs(),
                state.everyoneHasHadInitialTurn(), state.actedPlayerIds(), state.scheduledTaskDeadlines(), handEvaluator);
    }

    private static List<AggregateStateImage.CardState> cards(List<Card> cards) {
        return cards.stream().map(card -> new AggregateStateImage.CardState(card.rank(), card.suit())).toList();
    }

    private static List<Card> cardsFromState(List<AggregateStateImage.CardState> cards) {
        return cards.stream().map(card -> new Card(card.rank(), card.suit())).toList();
    }
}
