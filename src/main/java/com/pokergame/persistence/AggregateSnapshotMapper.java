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

/**
 * Defines the explicit compatibility boundary between mutable poker aggregates and
 * versioned persistence state.
 * <p>
 * Domain objects are deliberately mapped field by field so runtime collaborators,
 * locks, and framework objects can never leak into the WAL format.
 * </p>
 */
@Component
public final class AggregateSnapshotMapper {
    private final ObjectMapper objectMapper;
    private final HandEvaluatorService handEvaluator;

    /**
     * Creates the mapper with the same JSON and hand-evaluation collaborators used
     * by the running application.
     *
     * @param objectMapper  mapper configured for the application's record types
     * @param handEvaluator runtime collaborator that restored games must use
     */
    public AggregateSnapshotMapper(ObjectMapper objectMapper, HandEvaluatorService handEvaluator) {
        this.objectMapper = objectMapper;
        this.handEvaluator = handEvaluator;
    }

    /**
     * Captures one authoritative room/game aggregate as a detached state image.
     * A room without a game is valid because lobby mutations must be recoverable too.
     *
     * @param room        authoritative room state
     * @param currentHost current host after any lobby host transfer
     * @param game        active game, or {@code null} while the room is a lobby
     * @return encoded schema-versioned state image
     * @throws PersistenceException if the image cannot be encoded
     */
    public byte[] serialize(Room room, String currentHost, Game game) {
        AggregateStateImage.RoomState roomState = new AggregateStateImage.RoomState(
                room.getRoomId(), room.getRoomName(), room.getHostName(), room.getMaxPlayers(), room.getSmallBlind(),
                room.getBigBlind(), room.getBuyIn(), room.getPasswordForPersistence(), room.getCreatedAt(),
                room.getPlayersWithJoinTimeSnapshot(), room.isGameStarted());
        AggregateStateImage.GameState gameState = game == null ? null : toState(game);
        return write(new AggregateStateImage(AggregateStateImage.CURRENT_SCHEMA_VERSION, false, roomState,
                currentHost, gameState));
    }

    /**
     * Creates a durable tombstone before physical WAL deletion so a crash cannot
     * resurrect a room whose in-memory lifecycle already finished.
     *
     * @param roomId identity of the deleted aggregate
     * @return encoded deletion state image
     * @throws PersistenceException if the tombstone cannot be encoded
     */
    public byte[] serializeDeletion(String roomId) {
        AggregateStateImage.RoomState tombstone = new AggregateStateImage.RoomState(
                roomId, "deleted", "deleted", 2, 1, 2, 20, null, null, java.util.Map.of(), false);
        return write(new AggregateStateImage(AggregateStateImage.CURRENT_SCHEMA_VERSION, true, tombstone,
                "deleted", null));
    }

    /**
     * Rehydrates a state image only after validating its schema version. Explicit
     * reconstruction preserves domain invariants without replaying nondeterministic
     * commands such as shuffles and timestamp generation.
     *
     * @param bytes decrypted state-image bytes
     * @return reconstructed aggregate or deletion marker
     * @throws PersistenceException if the image is malformed or unsupported
     */
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

    /**
     * Centralizes JSON failures so callers see storage-domain failures rather than
     * serializer-specific exceptions.
     *
     * @param image detached state image to encode
     * @return encoded image bytes
     * @throws PersistenceException if serialization fails
     */
    private byte[] write(AggregateStateImage image) {
        try {
            return objectMapper.writeValueAsBytes(image);
        } catch (JacksonException e) {
            throw new PersistenceException("Cannot encode aggregate snapshot", e);
        }
    }

    /**
     * Selects only authoritative game fields needed for exact continuation; runtime
     * scheduling objects and service references are rebuilt separately.
     *
     * @param game live game to capture
     * @return detached persisted game state
     */
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

    /**
     * Captures private player state because chip accounting, hole cards, and
     * reconnect status must remain exact across a restart.
     *
     * @param player player to capture
     * @return detached persisted player state
     */
    private AggregateStateImage.PlayerState toState(Player player) {
        return new AggregateStateImage.PlayerState(
                player.getName(), player.getPlayerId(), cards(player.getHoleCards()), cards(player.getBestHand()),
                player.getHandRank(), player.getChips(), player.getCurrentBet(), player.getHasFolded(),
                player.getIsAllIn(), player.getIsOut(), player.getIsDisconnected(),
                player.getDisconnectDeadlineEpochMs(), player.getIsReadyForNextHand());
    }

    /**
     * Reconstructs a game from state rather than invoking normal game creation,
     * which would reshuffle cards, repost blinds, and change turn order.
     *
     * @param state persisted game state
     * @return rehydrated game ready for runtime collaborators to be attached
     */
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

    /**
     * Converts cards to storage records so the persistence schema is independent of
     * mutable domain implementation details.
     *
     * @param cards domain cards
     * @return detached card-state list
     */
    private static List<AggregateStateImage.CardState> cards(List<Card> cards) {
        return cards.stream().map(card -> new AggregateStateImage.CardState(card.rank(), card.suit())).toList();
    }

    /**
     * Reconstructs domain cards without drawing from a deck, preserving the exact
     * card order recorded before the crash.
     *
     * @param cards persisted card records
     * @return reconstructed domain cards
     */
    private static List<Card> cardsFromState(List<AggregateStateImage.CardState> cards) {
        return cards.stream().map(card -> new Card(card.rank(), card.suit())).toList();
    }
}
