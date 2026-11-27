package com.pokergame.persistence.snapshot;

import com.pokergame.dto.response.PublicGameStateResponse;
import com.pokergame.model.Card;
import com.pokergame.model.Game;
import com.pokergame.model.HandPayout;
import com.pokergame.model.Player;
import com.pokergame.model.Room;
import com.pokergame.persistence.PersistenceException;
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
 * into the WAL save files.
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
     * Copies the live room and optional game into an immutable {@link RoomSnapshot} while the
     * caller holds the room lock, without performing any JSON conversion or disk I/O.
     *
     * @param room the live room
     * @param game the live game, or {@code null} if the room is still a lobby
     * @return an immutable copy of the room and game
     */
    public RoomSnapshot captureSnapshot(Room room, Game game) {
        if (room == null) {
            throw new PersistenceException("Room cannot be null for aggregate state capture");
        }
        return new RoomSnapshot(false, toRoomState(room), toGameState(game));
    }

    /**
     * Encodes an already-captured {@link RoomSnapshot} into JSON bytes for saving to the WAL.
     *
     * @param snapshot the room snapshot to encode
     * @return the JSON bytes
     * @throws PersistenceException if JSON encoding fails
     */
    public byte[] serialize(RoomSnapshot snapshot) {
        if (snapshot == null) {
            throw new PersistenceException("State image cannot be null for serialization");
        }
        return write(snapshot);
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
            RoomSnapshot snapshot = objectMapper.readValue(bytes, RoomSnapshot.class);
            return restoreSnapshot(snapshot);
        } catch (JacksonException | IllegalArgumentException e) {
            throw new PersistenceException("Cannot decode aggregate snapshot", e);
        }
    }

    /**
     * Rebuilds fresh {@link Room} and {@link Game} objects from an in-memory {@link RoomSnapshot},
     * without touching JSON or disk. Used for rolling back a failed change in memory even if
     * disk saving or JSON encoding is unavailable.
     *
     * @param snapshot the captured room snapshot
     * @return the rebuilt room and optional game (or a deleted marker)
     * @throws PersistenceException if the snapshot is null or invalid
     */
    public RecoveredRoom restoreSnapshot(RoomSnapshot snapshot) {
        if (snapshot == null) {
            throw new PersistenceException("Unsupported in-memory aggregate image");
        }
        // If this snapshot marks a deleted room, just return a deleted result
        if (snapshot.deleted()) {
            return new RecoveredRoom(null, null, true);
        }
        RoomSnapshot.RoomState state = snapshot.room();
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
        if (snapshot.game() != null) {
            if (snapshot.game().gameId() == null || !snapshot.game().gameId().equals(state.roomId())) {
                throw new PersistenceException("Game identity does not match room identity in snapshot");
            }
            if (snapshot.game().pot() < 0) {
                throw new PersistenceException("Invalid negative pot in snapshot");
            }
            if (snapshot.game().readyGateState() == null) {
                throw new PersistenceException("Missing readyGateState in snapshot");
            }
            game = restoreGame(snapshot.game(), state.maxPlayers());
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
        Game.Snapshot snapshot = game.toSnapshot();
        List<RoomSnapshot.PlayerState> playerStates = game.getPlayers().stream().map(this::toPlayerState).toList();
        List<String> activePlayerIds = game.getActivePlayers().stream().map(Player::getPlayerId).toList();
        return new RoomSnapshot.GameState(
            game.getGameId(), playerStates, activePlayerIds,
            cards(snapshot.remainingDeck()), cards(game.getCommunityCards()), game.getPot(),
            game.getCurrentHighestBet(), game.getCurrentPhase(),
            game.isGameOver(), game.getSmallBlind(), game.getBigBlind(), snapshot.handContributions(),
            game.getReadyCountdownDeadlineEpochMs(),
            snapshot.allPlayersHadInitialTurn(), snapshot.actedPlayerIds(),
            snapshot.scheduledTimes(), snapshot.carryOverPot(),
            snapshot.lastFullRaise(), snapshot.previousBets(),
            snapshot.handSeatOrder(), snapshot.dealerPlayerId(), game.getSmallBlindPlayerId(),
            game.getBigBlindPlayerId(), snapshot.currentPlayerId(), toHandPayoutState(game.getLastHandPayout()),
            game.isFinalPotSettled(), game.getReadyGateState(), game.getHandNumber(),
            snapshot.timerVersions(), game.isPausedForDisconnectedSurvivor(),
            game.getPendingForfeitReason(), game.getPendingForfeitedPlayers(),
            game.getStateRevision(), toFinalGameState(game.getGameEndResult()));
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
        List<String> players = state.players() == null ? List.of() : state.players();
        String hostName = state.hostName() != null && players.contains(state.hostName())
                ? state.hostName()
                : players.stream().findFirst().orElse(state.hostName());
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
                .players(players)
                .playerMembershipIds(state.playerMembershipIds())
                .gameStarted(state.gameStarted())
                .inactivityDeadlineEpochMs(state.inactivityDeadlineEpochMs())
                .build();
    }

    private RoomSnapshot.RoomState toRoomState(Room room) {
        return new RoomSnapshot.RoomState(
                room.getRoomId(), room.getRoomName(), room.getHostName(), room.getMaxPlayers(), room.getSmallBlind(),
                room.getBigBlind(), room.getBuyIn(), room.getPasswordHash(), room.getCreatedAt(),
                room.getPlayers(), room.getPlayerMembershipIdsSnapshot(), room.isGameStarted(),
                room.getInactivityDeadlineEpochMs());
    }

    private byte[] write(RoomSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsBytes(snapshot);
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
                .scheduledTimes(state.scheduledTimes())
                .timerVersions(state.timerVersions())
                .carryOverPot(state.carryOverPot())
                .lastFullRaise(state.lastFullRaise())
                .previousBets(state.previousBets())
                .handSeatOrder(state.handSeatOrder())
                .dealerPlayerId(state.dealerPlayerId())
                .smallBlindPlayerId(state.smallBlindPlayerId())
                .bigBlindPlayerId(state.bigBlindPlayerId())
                .lastHandPayout(toHandPayout(state.lastHandPayout()))
                .finalPotSettled(state.finalPotSettled())
                .readyGateState(state.readyGateState())
                .handNumber(state.handNumber())
                .pausedForDisconnectedSurvivor(state.pausedForDisconnectedSurvivor())
                .pendingForfeitReason(state.pendingForfeitReason())
                .pendingForfeitedPlayers(state.pendingForfeitedPlayers())
                .stateRevision(state.stateRevision())
                .gameEndResult(fromFinalGameState(state.gameEndResult()))
                .build();
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
                finalState.stateRevision(),
                toGameEndState(finalState.gameEnd()));
    }

    private RoomSnapshot.GameEndState toGameEndState(PublicGameStateResponse.GameEnd gameEnd) {
        if (gameEnd == null) return null;
        return new RoomSnapshot.GameEndState(
                gameEnd.winner(),
                gameEnd.winnerChips(),
                gameEnd.isForfeit(),
                gameEnd.abandoned() ? Boolean.TRUE : null,
                gameEnd.reason(),
                gameEnd.forfeitedPlayers(),
                gameEnd.message());
    }

    private RoomSnapshot.FinalPlayerState toFinalPlayerState(PublicGameStateResponse.PlayerState player) {
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
                player.isDealer(),
                player.handRank(),
                cards(player.bestHand()),
                player.isWinner(),
                player.chipsWon(),
                cards(player.holeCards()),
                player.disconnectDeadlineEpochMs(),
                player.isReadyForNextHand());
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
                state.stateRevision(),
                null,
                null,
                fromGameEndState(state.gameEnd()));
    }

    private PublicGameStateResponse.GameEnd fromGameEndState(RoomSnapshot.GameEndState state) {
        if (state == null) return null;
        return new PublicGameStateResponse.GameEnd(
                state.winner(),
                state.winnerChips(),
                state.isForfeit(),
                Boolean.TRUE.equals(state.abandoned()),
                state.reason(),
                state.forfeitedPlayers(),
                state.message());
    }

    private PublicGameStateResponse.PlayerState fromFinalPlayerState(RoomSnapshot.FinalPlayerState state) {
        if (state == null) return null;
        return new PublicGameStateResponse.PlayerState(
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
                state.isDealer(),
                state.handRank(),
                cardsFromState(state.bestHand()),
                state.isWinner(),
                state.chipsWon(),
                cardsFromState(state.holeCards()),
                state.disconnectDeadlineEpochMs(),
                state.isReadyForNextHand());
    }

    private static RoomSnapshot.HandPayoutState toHandPayoutState(HandPayout payout) {
        if (payout == null) return null;
        return new RoomSnapshot.HandPayoutState(payout.potWinnings(), payout.refunds(),
                payout.winnerIds(), payout.remainder(), payout.wentToShowdown());
    }

    private static HandPayout toHandPayout(RoomSnapshot.HandPayoutState state) {
        if (state == null) return null;
        return new HandPayout(state.potWinnings(), state.refunds(),
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
