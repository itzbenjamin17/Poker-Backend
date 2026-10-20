package com.pokergame.persistence.snapshot;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.pokergame.model.Card;
import com.pokergame.model.Game;
import com.pokergame.model.Player;
import com.pokergame.model.Room;
import com.pokergame.service.HandEvaluatorService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;
import com.pokergame.dto.response.GameEndResponse;
import com.pokergame.dto.response.PublicGameStateResponse;
import com.pokergame.dto.response.PublicPlayerState;
import com.pokergame.persistence.config.PersistenceException;

/**
 * Converts live game data (Rooms and Games) into a clean snapshot format for saving to disk,
 * and vice versa.
 * <p>
 * We map every field manually instead of using automatic serialization. This ensures that internal
 * code details—like thread locks, timers, or service dependencies—never accidentally get saved
 * into the database files.
 * </p>
 */
@Component
public final class AggregateSnapshotMapper {
    private final ObjectMapper objectMapper;
    private final HandEvaluatorService handEvaluator;

    /**
     * Creates the mapper with the JSON and hand-evaluation services used by the application.
     *
     * @param objectMapper  mapper configured for the application's records
     * @param handEvaluator hand evaluator that restored games will use
     */
    public AggregateSnapshotMapper(ObjectMapper objectMapper, HandEvaluatorService handEvaluator) {
        this.objectMapper = objectMapper;
        this.handEvaluator = handEvaluator;
    }

    /**
     * Takes a room and its game and turns it into a snapshot that can be saved.
     * A room without a game is also supported, so we can save a room even if it's just a lobby.
     *
     * @param room        the room being saved
     * @param currentHost the current host, which could be different from the original creator
     * @param game        the active game, or {@code null} if the room is still a lobby
     * @return the bytes of the saved snapshot
     * @throws PersistenceException if the snapshot cannot be created
     */
    public byte[] serialize(Room room, String currentHost, Game game) {
        // Create a snapshot of the room's current state
        AggregateStateImage.RoomState roomState = new AggregateStateImage.RoomState(
                room.getRoomId(), room.getRoomName(), room.getHostName(), room.getMaxPlayers(), room.getSmallBlind(),
                room.getBigBlind(), room.getBuyIn(), room.getPasswordForPersistence(), room.getCreatedAt(),
                room.getPlayersWithJoinTimeSnapshot(), room.isGameStarted());
        // If there is an active game, capture its state as well
        AggregateStateImage.GameState gameState = game == null ? null : toState(game);
        return write(new AggregateStateImage(AggregateStateImage.CURRENT_SCHEMA_VERSION, false, roomState,
                currentHost, gameState));
    }

    /**
     * Creates a special "deleted" snapshot so that if the app crashes while deleting a room,
     * the room isn't accidentally restored on restart.
     *
     * @param roomId the ID of the room being deleted
     * @return the bytes of the deleted snapshot
     * @throws PersistenceException if the deletion snapshot cannot be created
     */
    public byte[] serializeDeletion(String roomId) {
        AggregateStateImage.RoomState tombstone = new AggregateStateImage.RoomState(
                roomId, "deleted", "deleted", 2, 1, 2, 20, null, null, java.util.Map.of(), false);
        return write(new AggregateStateImage(AggregateStateImage.CURRENT_SCHEMA_VERSION, true, tombstone,
                "deleted", null));
    }

    /**
     * Reads a snapshot from bytes and turns it back into a usable Room and Game.
     * It checks the schema version first, then carefully reconstructs the objects 
     * to keep the exact same state without accidentally triggering side effects like card shuffling.
     *
     * @param bytes the saved snapshot bytes
     * @return the reconstructed Room and Game (or a marker saying it was deleted)
     * @throws PersistenceException if the snapshot is corrupted or from an older unsupported version
     */
    public RecoveredAggregate deserialize(byte[] bytes) {
        try {
            AggregateStateImage image = objectMapper.readValue(bytes, AggregateStateImage.class);
            // Verify that we can actually read this snapshot version
            if (image.schemaVersion() != AggregateStateImage.CURRENT_SCHEMA_VERSION) {
                throw new PersistenceException("Unsupported aggregate snapshot schema: " + image.schemaVersion());
            }
            // If this snapshot marks a deleted room, just return a deleted result
            if (image.deleted()) {
                return new RecoveredAggregate(null, null, null, true);
            }
            AggregateStateImage.RoomState state = image.room();
            if (state == null || state.roomId() == null || state.roomId().trim().isEmpty()) {
                throw new PersistenceException("Missing required room state in aggregate snapshot");
            }
            if (state.maxPlayers() < 2 || state.maxPlayers() > 10 || state.smallBlind() < 1
                    || state.bigBlind() < 2 * state.smallBlind() || state.buyIn() < state.bigBlind()) {
                throw new PersistenceException("Invalid room parameters in snapshot");
            }

            // If there was an active game, rebuild and strictly validate it
            Game game = null;
            if (image.game() != null) {
                if (image.game().gameId() == null || !image.game().gameId().equals(state.roomId())) {
                    throw new PersistenceException("Game identity does not match room identity in snapshot");
                }
                if (image.game().pot() < 0) {
                    throw new PersistenceException("Invalid negative pot in snapshot");
                }
                game = restoreGame(image.game());
            }

            // Room membership is authoritative independently of retained final Game rows.
            Map<String, LocalDateTime> joinTimes = state.playersWithJoinTime() == null
                    ? java.util.Map.of() : state.playersWithJoinTime();
            List<String> roomMembers = List.copyOf(joinTimes.keySet());
            String host = image.currentHost();
            String reconciledHost = host != null && roomMembers.contains(host) ? host
                    : state.originalHost() != null && roomMembers.contains(state.originalHost()) ? state.originalHost()
                    : roomMembers.stream().findFirst().orElse(null);
            Room room = Room.restoreBuilder()
                    .roomId(state.roomId())
                    .roomName(state.roomName())
                    .hostName(state.originalHost())
                    .maxPlayers(state.maxPlayers())
                    .smallBlind(state.smallBlind())
                    .bigBlind(state.bigBlind())
                    .buyIn(state.buyIn())
                    .password(state.password())
                    .createdAt(state.createdAt())
                    .playersWithJoinTime(joinTimes)
                    .gameStarted(state.gameStarted())
                    .build();

            return new RecoveredAggregate(room, reconciledHost, game, false);
        } catch (JacksonException | IllegalArgumentException e) {
            throw new PersistenceException("Cannot decode aggregate snapshot", e);
        }
    }

    /**
     * Wraps the standard JSON writing so that errors are easy to catch and log.
     *
     * @param image the snapshot data to convert to bytes
     * @return the saved bytes
     * @throws PersistenceException if the conversion fails
     */
    private byte[] write(AggregateStateImage image) {
        try {
            return objectMapper.writeValueAsBytes(image);
        } catch (JacksonException e) {
            throw new PersistenceException("Cannot encode aggregate snapshot", e);
        }
    }

    /**
     * Copies only the important game data we need to pause and resume the exact same hand.
     * Background tasks and runtime services are ignored here and reattached later.
     *
     * @param game the running game
     * @return a simple snapshot of the game
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
                game.getScheduledTaskDeadlinesSnapshot(), game.getPreHandCarryOver(),
                game.getLastFullRaiseByStreetSnapshot(), game.getLastActedWagerByPlayerSnapshot(),
                game.getHandSeatOrderSnapshot(), game.getDealerAnchorPlayerId(), game.getSmallBlindPlayerId(),
                game.getBigBlindPlayerId(), settlementState(game.getLastSettlementResult()),
                game.isTerminalCarryOverSettled(), game.getReadyGateState(), game.getHandNumber(),
                game.getScheduledTaskGenerationsSnapshot(), game.isForfeitPending(), game.getStateRevision(),
                toTerminalResultState(game.getRetainedTerminalResult()));
    }

    /**
     * Captures player data like chip counts, their cards, and if they disconnected,
     * so that everything is exactly the same when they come back.
     *
     * @param player the player to save
     * @return a simple snapshot of the player
     */
    private AggregateStateImage.PlayerState toState(Player player) {
        return new AggregateStateImage.PlayerState(
                player.getName(), player.getPlayerId(), cards(player.getHoleCards()), cards(player.getBestHand()),
                player.getHandRank(), player.getChips(), player.getCurrentBet(), player.getHasFolded(),
                player.getIsAllIn(), player.getIsOut(), player.getIsDisconnected(),
                player.getDisconnectDeadlineEpochMs(), player.getIsReadyForNextHand());
    }

    /**
     * Restores a Game from a snapshot. We do this carefully instead of using normal game creation,
     * so we don't accidentally reshuffle cards, take new blinds, or mess up whose turn it is.
     *
     * @param state the saved game snapshot
     * @return a recreated game, ready to continue playing
     */
    private Game restoreGame(AggregateStateImage.GameState state) {
        if (state.stateRevision() < 0) {
            throw new PersistenceException("Invalid negative public state revision in snapshot");
        }
        if (state.retainedTerminalResult() != null) {
            validateTerminalResult(state.gameId(), state.retainedTerminalResult());
        }
        // Rebuild all the players first
        List<Player> players = state.players().stream().map(player -> Player.restoreBuilder()
                .name(player.name())
                .playerId(player.playerId())
                .holeCards(cardsFromState(player.holeCards()))
                .bestHand(cardsFromState(player.bestHand()))
                .handRank(player.handRank())
                .chips(player.chips())
                .currentBet(player.currentBet())
                .hasFolded(player.folded())
                .allIn(player.allIn())
                .out(player.out())
                .disconnected(player.disconnected())
                .disconnectDeadlineEpochMs(player.disconnectDeadlineEpochMs())
                .readyForNextHand(player.readyForNextHand())
                .build()).toList();
        // Put the game back together with the exact same deck, pot, and turn order
        return Game.restoreBuilder()
                .gameId(state.gameId())
                .players(players)
                .activePlayerIds(state.activePlayerIds())
                .remainingDeck(cardsFromState(state.remainingDeck()))
                .communityCards(cardsFromState(state.communityCards()))
                .pot(state.pot())
                .dealerPosition(state.dealerPosition())
                .smallBlindPosition(state.smallBlindPosition())
                .bigBlindPosition(state.bigBlindPosition())
                .currentPlayerPosition(state.currentPlayerPosition())
                .currentHighestBet(state.currentHighestBet())
                .currentPhase(state.currentPhase())
                .gameOver(state.gameOver())
                .smallBlind(state.smallBlind())
                .bigBlind(state.bigBlind())
                .handContributions(state.handContributions())
                .readyCountdownActive(state.readyCountdownActive())
                .readyCountdownDeadlineEpochMs(state.readyCountdownDeadlineEpochMs())
                .everyoneHasHadInitialTurn(state.everyoneHasHadInitialTurn())
                .actedPlayersInRound(state.actedPlayerIds())
                .scheduledTaskDeadlines(state.scheduledTaskDeadlines())
                .scheduledTaskGenerations(state.scheduledTaskGenerations())
                .preHandCarryOver(state.preHandCarryOver())
                .lastFullRaiseByStreet(state.lastFullRaiseByStreet())
                .lastActedWagerByPlayer(state.lastActedWagerByPlayer())
                .handSeatOrder(state.handSeatOrder())
                .dealerAnchorPlayerId(state.dealerAnchorPlayerId())
                .smallBlindPlayerId(state.smallBlindPlayerId())
                .bigBlindPlayerId(state.bigBlindPlayerId())
                .lastSettlementResult(settlementResult(state.lastSettlementResult()))
                .terminalCarryOverSettled(state.terminalCarryOverSettled())
                .readyGateState(state.readyGateState())
                .handNumber(state.handNumber())
                .forfeitPending(state.forfeitPending())
                .stateRevision(state.stateRevision())
                .retainedTerminalResult(fromTerminalResultState(state.retainedTerminalResult()))
                .handEvaluator(handEvaluator) // Reattach the service needed for evaluating hands
                .build();
    }

    private void validateTerminalResult(String expectedGameId, AggregateStateImage.TerminalResultState state) {
        if (state == null) return;
        if (state.gameId() == null || !state.gameId().equals(expectedGameId)) {
            throw new PersistenceException("Retained terminal result gameId does not match game identity");
        }
        if (state.winner() == null || state.winner().trim().isEmpty()) {
            throw new PersistenceException("Retained terminal result missing winner name");
        }
        if (state.winnerChips() < 0) {
            throw new PersistenceException("Retained terminal result has invalid negative winner chips");
        }
        if (state.finalState() == null) {
            throw new PersistenceException("Retained terminal result missing final state projection");
        }
        if (state.finalState().stateRevision() < 0) {
            throw new PersistenceException("Retained terminal result has invalid negative state revision");
        }
        if (state.finalState().players() == null) {
            throw new PersistenceException("Retained terminal result missing final players projection");
        }
    }

    private AggregateStateImage.TerminalResultState toTerminalResultState(GameEndResponse response) {
        if (response == null) return null;
        return new AggregateStateImage.TerminalResultState(
                response.type(),
                response.gameId(),
                response.winner(),
                response.winnerChips(),
                response.isForfeit(),
                response.message(),
                toFinalGameState(response.finalState()));
    }

    private AggregateStateImage.FinalGameState toFinalGameState(PublicGameStateResponse finalState) {
        if (finalState == null) return null;
        return new AggregateStateImage.FinalGameState(
                finalState.maxPlayers(),
                finalState.pot(),
                finalState.pots(),
                finalState.uncalledAmount(),
                finalState.phase(),
                finalState.currentBet(),
                cards(finalState.communityCards()),
                finalState.players() == null ? null : finalState.players().stream().map(this::toFinalPlayerState).toList(),
                finalState.currentPlayerName(),
                finalState.currentPlayerId(),
                finalState.winners(),
                finalState.winningsPerPlayer(),
                finalState.isAutoAdvancing(),
                finalState.autoAdvanceMessage(),
                finalState.isReadyCountdownActive(),
                finalState.readyCountdownDeadlineEpochMs(),
                finalState.claimWinAvailable(),
                finalState.claimWinPlayerName(),
                finalState.handSettled(),
                finalState.carryOverAmount(),
                finalState.legalActions(),
                finalState.minimumBetAmount(),
                finalState.minimumRaiseAmount(),
                finalState.handNumber(),
                finalState.readyGateState(),
                finalState.serverTimeEpochMs(),
                finalState.stateRevision());
    }

    private AggregateStateImage.FinalPlayerState toFinalPlayerState(PublicPlayerState player) {
        if (player == null) return null;
        return new AggregateStateImage.FinalPlayerState(
                player.id(),
                player.name(),
                player.chips(),
                player.currentBet(),
                player.status(),
                player.isAllIn(),
                player.isCurrentPlayer(),
                player.hasFolded(),
                player.isSmallBlind(),
                player.isBigBlind(),
                player.handRank(),
                cards(player.bestHand()),
                player.isWinner(),
                player.chipsWon(),
                cards(player.holeCards()),
                player.disconnectDeadlineEpochMs(),
                player.isReadyForNextHand());
    }

    private GameEndResponse fromTerminalResultState(AggregateStateImage.TerminalResultState state) {
        if (state == null) return null;
        return new GameEndResponse(
                state.type(),
                state.gameId(),
                state.winner(),
                state.winnerChips(),
                state.isForfeit(),
                state.message(),
                fromFinalGameState(state.finalState()));
    }

    private PublicGameStateResponse fromFinalGameState(AggregateStateImage.FinalGameState state) {
        if (state == null) return null;
        return new PublicGameStateResponse(
                state.maxPlayers(),
                state.pot(),
                state.pots(),
                state.uncalledAmount(),
                state.phase(),
                state.currentBet(),
                cardsFromState(state.communityCards()),
                state.players() == null ? null : state.players().stream().map(this::fromFinalPlayerState).toList(),
                state.currentPlayerName(),
                state.currentPlayerId(),
                state.winners(),
                state.winningsPerPlayer(),
                state.isAutoAdvancing(),
                state.autoAdvanceMessage(),
                state.isReadyCountdownActive(),
                state.readyCountdownDeadlineEpochMs(),
                state.claimWinAvailable(),
                state.claimWinPlayerName(),
                state.handSettled(),
                state.carryOverAmount(),
                state.legalActions(),
                state.minimumBetAmount(),
                state.minimumRaiseAmount(),
                state.handNumber(),
                state.readyGateState(),
                state.serverTimeEpochMs(),
                state.stateRevision());
    }

    private PublicPlayerState fromFinalPlayerState(AggregateStateImage.FinalPlayerState state) {
        if (state == null) return null;
        return new PublicPlayerState(
                state.id(),
                state.name(),
                state.chips(),
                state.currentBet(),
                state.status(),
                state.isAllIn(),
                state.isCurrentPlayer(),
                state.hasFolded(),
                state.isSmallBlind(),
                state.isBigBlind(),
                state.handRank(),
                cardsFromState(state.bestHand()),
                state.isWinner(),
                state.chipsWon(),
                cardsFromState(state.holeCards()),
                state.disconnectDeadlineEpochMs(),
                state.isReadyForNextHand());
    }

    private static AggregateStateImage.SettlementState settlementState(com.pokergame.model.HandSettlementResult result) {
        if (result == null) return null;
        return new AggregateStateImage.SettlementState(result.contestedAwards(), result.refunds(),
                result.winnerIds(), result.remainder(), result.settled());
    }

    private static com.pokergame.model.HandSettlementResult settlementResult(AggregateStateImage.SettlementState state) {
        if (state == null) return null;
        return new com.pokergame.model.HandSettlementResult(state.contestedAwards(), state.refunds(),
                state.winnerIds(), state.remainder(), state.settled());
    }

    /**
     * Converts a list of cards into a simple format that's safe to save,
     * keeping it completely separate from how the game actually manages cards in memory.
     *
     * @param cards the cards from the game
     * @return a safe-to-save list of card records
     */
    private static List<AggregateStateImage.CardState> cards(List<Card> cards) {
        if (cards == null) return null;
        return cards.stream().map(card -> new AggregateStateImage.CardState(card.rank(), card.suit())).toList();
    }

    /**
     * Puts the cards back exactly in the order they were saved,
     * skipping any random shuffling so the deck stays identical.
     *
     * @param cards the saved card records
     * @return the restored game cards
     */
    private static List<Card> cardsFromState(List<AggregateStateImage.CardState> cards) {
        if (cards == null) return null;
        return cards.stream().map(card -> new Card(card.rank(), card.suit())).toList();
    }
}
