package com.pokergame.persistence.snapshot;

import com.pokergame.dto.response.GameEndResponse;
import com.pokergame.dto.response.PublicGameStateResponse;
import com.pokergame.dto.response.PublicPlayerState;
import com.pokergame.model.Card;
import com.pokergame.model.Game;
import com.pokergame.model.HandSettlementResult;
import com.pokergame.model.Player;
import com.pokergame.model.Room;
import com.pokergame.persistence.PersistenceException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

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
public final class RoomSnapshotMapper {
    private final ObjectMapper objectMapper;

    /**
     * Creates the mapper with the JSON mapper used by the application.
     *
     * @param objectMapper mapper configured for the application's records
     */
    public RoomSnapshotMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.rebuild()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                .disable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
                .build();
    }

    /**
     * Captures an immutable room snapshot while the caller owns the room lock.
     * All live domain fields, players, and cards are converted into independent immutable records.
     * No JSON serialization or I/O is performed during capture.
     *
     * @param room the room aggregate
     * @param game the game aggregate, or null if room is a lobby
     * @return independent immutable room snapshot
     */
    public RoomSnapshot captureStateImage(Room room, Game game) {
        if (room == null) {
            throw new PersistenceException("Room cannot be null for aggregate state capture");
        }
        return new RoomSnapshot(false, toRoomState(room), toGameState(game));
    }

    /**
     * Serializes an already-captured immutable room snapshot to JSON bytes.
     * The caller retains the established persistence ordering boundary.
     *
     * @param image the room snapshot to serialize
     * @return the serialized bytes
     * @throws PersistenceException if JSON serialization fails
     */
    public byte[] serialize(RoomSnapshot image) {
        if (image == null) {
            throw new PersistenceException("State image cannot be null for serialization");
        }
        return write(image);
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
        return write(new RoomSnapshot(true, null, null));
    }

    /**
     * Reads a snapshot from bytes and turns it back into a usable Room and Game.
     * It parses the snapshot strictly and reconstructs the objects to keep the exact same
     * state without accidentally triggering side effects like card shuffling.
     *
     * @param bytes the saved snapshot bytes
     * @return the reconstructed Room and Game (or a marker saying it was deleted)
     * @throws PersistenceException if the snapshot is corrupted or invalid
     */
    public RecoveredRoom deserialize(byte[] bytes) {
        try {
            RoomSnapshot image = objectMapper.readValue(bytes, RoomSnapshot.class);
            return restoreStateImage(image);
        } catch (JacksonException | IllegalArgumentException e) {
            throw new PersistenceException("Cannot decode aggregate snapshot", e);
        }
    }

    /**
     * Reconstructs independent aggregates from an immutable image without JSON or disk access.
     * Used for live rollback so a serialization outage cannot discard accepted gameplay.
     *
     * @param image captured aggregate data
     * @return independently reconstructed room and game
     * @throws PersistenceException if the image is invalid or null
     */
    public RecoveredRoom restoreStateImage(RoomSnapshot image) {
        if (image == null) {
            throw new PersistenceException("Unsupported in-memory aggregate image");
        }
        // If this snapshot marks a deleted room, just return a deleted result
        if (image.deleted()) {
            return new RecoveredRoom(null, null, true);
        }
        RoomSnapshot.RoomState state = image.room();
        if (state == null || state.roomId() == null || state.roomId().trim().isEmpty()) {
            throw new PersistenceException("Missing required room state in aggregate snapshot");
        }
        if (state.maxPlayers() < Room.MIN_PLAYERS || state.maxPlayers() > Room.MAX_PLAYERS
                || state.smallBlind() < Room.MIN_SMALL_BLIND
                || state.bigBlind() < Room.BIG_BLIND_MULTIPLE * state.smallBlind()
                || state.buyIn() < state.bigBlind()) {
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
            if (image.game().readyGateState() == null) {
                throw new PersistenceException("Missing readyGateState in snapshot");
            }
            game = restoreGame(image.game(), state.maxPlayers());
        }

        return new RecoveredRoom(restoreRoom(state), game, false);
    }

    /**
     * Copies only the important game data we need to pause and resume the exact same hand.
     * Background tasks and runtime services are ignored here and reattached later.
     *
     * @param game the running game
     * @return a simple snapshot of the game
     */
    RoomSnapshot.GameState toGameState(Game game) {
        if (game == null) {
            return null;
        }
        List<RoomSnapshot.PlayerState> playerStates = game.getPlayers().stream().map(this::toPlayerState).toList();
        List<String> activePlayerIds = game.getActivePlayers().stream().map(Player::getPlayerId).toList();
        return new RoomSnapshot.GameState(
            game.getGameId(), playerStates, activePlayerIds,
            cards(game.getRemainingDeckSnapshot()), cards(game.getCommunityCards()), game.getPot(),
            game.getCurrentHighestBet(), game.getCurrentPhase(),
            game.isGameOver(), game.getSmallBlind(), game.getBigBlind(), game.getHandContributionsSnapshot(),
            game.getReadyCountdownDeadlineEpochMs(),
            game.hasAllPlayersHadInitialTurn(), game.getActedPlayerIdsSnapshot(),
            game.getScheduledTaskDeadlinesSnapshot(), game.getCarryOverPot(),
            game.getLastFullRaise(), game.getLastActedWagerByPlayerSnapshot(),
            game.getHandSeatOrderSnapshot(), game.getDealerPlayerId(), game.getSmallBlindPlayerId(),
            game.getBigBlindPlayerId(), game.getCurrentPlayerId(), settlementState(game.getLastSettlementResult()),
            game.isFinalPotSettled(), game.getReadyGateState(), game.getHandNumber(),
            game.getTimerVersionsSnapshot(), game.isPausedForDisconnectedSurvivor(), game.getStateRevision(),
            toGameResultState(game.getGameEndResult()));
    }

    /**
     * Captures player data like chip counts, their cards, and if they disconnected,
     * so that everything is exactly the same when they come back.
     *
     * @param player the player to save
     * @return a simple snapshot of the player
     */
    RoomSnapshot.PlayerState toPlayerState(Player player) {
        return new RoomSnapshot.PlayerState(
                player.getName(), player.getPlayerId(), cards(player.getHoleCards()), cards(player.getBestHand()),
                player.getHandRank(), player.getChips(), player.getCurrentBet(), player.hasFolded(),
                player.isAllIn(), player.isOut(), player.isDisconnected(),
                player.getDisconnectDeadlineEpochMs(), player.isReadyForNextHand());
    }

    /**
     * Restores a Room from a persisted room snapshot.
     *
     * @param state the saved room snapshot
     * @return reconstructed room instance
     */
    Room restoreRoom(RoomSnapshot.RoomState state) {
        // Room membership is authoritative; the host must be one of the members.
        Map<String, LocalDateTime> joinTimes = state.playersWithJoinTime() == null
                ? Map.of() : state.playersWithJoinTime();
        String hostName = state.hostName() != null && joinTimes.containsKey(state.hostName())
                ? state.hostName()
                : joinTimes.keySet().stream().findFirst().orElse(state.hostName());
        return Room.restoreBuilder()
                .roomId(state.roomId())
                .roomName(state.roomName())
                .hostName(hostName)
                .maxPlayers(state.maxPlayers())
                .smallBlind(state.smallBlind())
                .bigBlind(state.bigBlind())
                .buyIn(state.buyIn())
                .passwordHash(state.password())
                .createdAt(state.createdAt())
                .playersWithJoinTime(joinTimes)
                .playerMembershipIds(state.playerMembershipIds())
                .gameStarted(state.gameStarted())
                .inactivityDeadlineEpochMs(state.inactivityDeadlineEpochMs())
                .build();
    }

    private RoomSnapshot.RoomState toRoomState(Room room) {
        return new RoomSnapshot.RoomState(
                room.getRoomId(), room.getRoomName(), room.getHostName(), room.getMaxPlayers(), room.getSmallBlind(),
                room.getBigBlind(), room.getBuyIn(), room.getPasswordHash(), room.getCreatedAt(),
                room.getPlayersWithJoinTimeSnapshot(), room.getPlayerMembershipIdsSnapshot(), room.isGameStarted(),
                room.getInactivityDeadlineEpochMs());
    }

    private byte[] write(RoomSnapshot image) {
        try {
            return objectMapper.writeValueAsBytes(image);
        } catch (JacksonException e) {
            throw new PersistenceException("Cannot encode aggregate snapshot", e);
        }
    }

    private Game restoreGame(RoomSnapshot.GameState state, int maxPlayers) {
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
                .maxPlayers(maxPlayers)
                .players(players)
                .activePlayerIds(state.activePlayerIds())
                .remainingDeck(cardsFromState(state.remainingDeck()))
                .communityCards(cardsFromState(state.communityCards()))
                .pot(state.pot())
                .currentPlayerId(state.currentPlayerId())
                .currentHighestBet(state.currentHighestBet())
                .currentPhase(state.currentPhase())
                .gameOver(state.gameOver())
                .smallBlind(state.smallBlind())
                .bigBlind(state.bigBlind())
                .handContributions(state.handContributions())
                .readyCountdownDeadlineEpochMs(state.readyCountdownDeadlineEpochMs())
                .allPlayersHadInitialTurn(state.allPlayersHadInitialTurn())
                .actedPlayersInRound(state.actedPlayerIds())
                .scheduledTaskDeadlines(state.scheduledTaskDeadlines())
                .timerVersions(state.timerVersions())
                .carryOverPot(state.carryOverPot())
                .lastFullRaise(state.lastFullRaise())
                .lastActedWagerByPlayer(state.lastActedWagerByPlayer())
                .handSeatOrder(state.handSeatOrder())
                .dealerPlayerId(state.dealerPlayerId())
                .smallBlindPlayerId(state.smallBlindPlayerId())
                .bigBlindPlayerId(state.bigBlindPlayerId())
                .lastSettlementResult(settlementResult(state.lastSettlementResult()))
                .finalPotSettled(state.finalPotSettled())
                .readyGateState(state.readyGateState())
                .handNumber(state.handNumber())
                .pausedForDisconnectedSurvivor(state.pausedForDisconnectedSurvivor())
                .stateRevision(state.stateRevision())
                .gameEndResult(fromGameResultState(state.gameEndResult()))
                .build();
    }

    private RoomSnapshot.GameResultState toGameResultState(GameEndResponse response) {
        if (response == null) return null;
        return new RoomSnapshot.GameResultState(
                response.type(),
                response.gameId(),
                response.winner(),
                response.winnerChips(),
                response.isForfeit(),
                response.abandoned() ? Boolean.TRUE : null,
                response.reason(),
                response.message(),
                toFinalGameState(response.finalState()));
    }

    private RoomSnapshot.FinalGameState toFinalGameState(PublicGameStateResponse finalState) {
        if (finalState == null) return null;
        return new RoomSnapshot.FinalGameState(
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

    private RoomSnapshot.FinalPlayerState toFinalPlayerState(PublicPlayerState player) {
        return new RoomSnapshot.FinalPlayerState(
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

    private GameEndResponse fromGameResultState(RoomSnapshot.GameResultState state) {
        if (state == null) return null;
        return new GameEndResponse(
                state.type(),
                state.gameId(),
                state.winner(),
                state.winnerChips(),
                state.isForfeit(),
                Boolean.TRUE.equals(state.abandoned()),
                state.reason(),
                state.message(),
                fromFinalGameState(state.finalState()));
    }

    private PublicGameStateResponse fromFinalGameState(RoomSnapshot.FinalGameState state) {
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

    private PublicPlayerState fromFinalPlayerState(RoomSnapshot.FinalPlayerState state) {
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

    private static RoomSnapshot.SettlementState settlementState(HandSettlementResult result) {
        if (result == null) return null;
        return new RoomSnapshot.SettlementState(result.contestedAwards(), result.refunds(),
                result.winnerIds(), result.remainder(), result.wentToShowdown());
    }

    private static HandSettlementResult settlementResult(RoomSnapshot.SettlementState state) {
        if (state == null) return null;
        return new HandSettlementResult(state.contestedAwards(), state.refunds(),
                state.winnerIds(), state.remainder(), state.wentToShowdown());
    }

    private static RoomSnapshot.CardState toCardState(Card card) {
        return new RoomSnapshot.CardState(card.rank(), card.suit());
    }

    private static Card fromCardState(RoomSnapshot.CardState state) {
        if (state == null) return null;
        return new Card(state.rank(), state.suit());
    }

    private static List<RoomSnapshot.CardState> cards(List<Card> cards) {
        if (cards == null) return null;
        return cards.stream().map(RoomSnapshotMapper::toCardState).toList();
    }

    private static Card requiredCard(RoomSnapshot.CardState state) {
        if (state == null) {
            throw new PersistenceException("Null card in aggregate snapshot");
        }
        return fromCardState(state);
    }

    private static List<Card> cardsFromState(List<RoomSnapshot.CardState> cards) {
        if (cards == null) return null;
        return cards.stream().map(RoomSnapshotMapper::requiredCard).toList();
    }
}
