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
import java.util.HashSet;
import java.util.Set;
import java.util.Objects;
import java.time.LocalDateTime;
import com.pokergame.dto.response.GameEndResponse;
import com.pokergame.dto.response.PublicGameStateResponse;
import com.pokergame.dto.response.PublicPlayerState;
import com.pokergame.persistence.config.PersistenceException;
import com.pokergame.enums.ReadyGateState;
import com.pokergame.model.HandSettlementResult;
import tools.jackson.databind.JsonNode;

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
     * Captures an immutable state image while the caller owns the room coordination lease.
     * All live domain fields, players, and cards are converted into independent immutable records.
     * No JSON serialization or I/O is performed during capture.
     *
     * @param room the room aggregate
     * @param currentHost the current host
     * @param game the game aggregate, or null if room is a lobby
     * @return independent immutable state image
     */
    public AggregateStateImage captureStateImage(Room room, String currentHost, Game game) {
        if (room == null) {
            throw new PersistenceException("Room cannot be null for aggregate state capture");
        }
        AggregateStateImage.RoomState roomState;
        AggregateStateImage.GameState gameState;
        roomState = toRoomState(room);
        gameState = toState(game);
        return new AggregateStateImage(AggregateStateImage.CURRENT_SCHEMA_VERSION, false, roomState,
                currentHost, gameState);
    }

    private AggregateStateImage.RoomState toRoomState(Room room) {
        return new AggregateStateImage.RoomState(
                room.getRoomId(), room.getRoomName(), room.getHostName(), room.getMaxPlayers(), room.getSmallBlind(),
                room.getBigBlind(), room.getBuyIn(), room.getPasswordHash(), room.getCreatedAt(),
                room.getPlayersWithJoinTimeSnapshot(), room.getPlayerMembershipIdsSnapshot(), room.isGameStarted(),
                room.getInactivityDeadlineEpochMs());
    }

    AggregateStateImage.RoomState toState(Room room, String currentHost) {
        if (room == null) {
            return null;
        }
        return toRoomState(room);
    }

    /**
     * Serializes an already-captured immutable state image to JSON bytes.
     * The caller retains the established persistence ordering boundary.
     *
     * @param image the state image to serialize
     * @return the serialized bytes
     * @throws PersistenceException if JSON serialization fails
     */
    public byte[] serialize(AggregateStateImage image) {
        if (image == null) {
            throw new PersistenceException("State image cannot be null for serialization");
        }
        return write(image);
    }

    /**
     * Captures and serializes a room and game while the caller owns the room coordination lease.
     *
     * @param room        the room being saved
     * @param currentHost the current host, which could be different from the original creator
     * @param game        the active game, or {@code null} if the room is still a lobby
     * @return the bytes of the saved snapshot
     * @throws PersistenceException if the snapshot cannot be created
     */
    public byte[] serialize(Room room, String currentHost, Game game) {
        AggregateStateImage image = captureStateImage(room, currentHost, game);
        return serialize(image);
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
                roomId, "deleted", "deleted", 2, 1, 2, 20, null, null, Map.of(), false);
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
            JsonNode root = objectMapper.readTree(bytes);
            int schemaVersion = root.path("schemaVersion").asInt(-1);
            // Verify that we can actually read this snapshot version
            if (schemaVersion != AggregateStateImage.CURRENT_SCHEMA_VERSION) {
                throw new PersistenceException("Unsupported aggregate snapshot schema: " + schemaVersion);
            }
            AggregateStateImage image = objectMapper.treeToValue(root, AggregateStateImage.class);
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
     * @throws PersistenceException if the image is invalid or unsupported
     */
    public RecoveredAggregate restoreStateImage(AggregateStateImage image) {
        if (image == null || image.schemaVersion() != AggregateStateImage.CURRENT_SCHEMA_VERSION) {
            throw new PersistenceException("Unsupported in-memory aggregate image");
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
                ? Map.of() : state.playersWithJoinTime();
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
                .passwordHash(state.password())
                .createdAt(state.createdAt())
                .playersWithJoinTime(joinTimes)
                .playerMembershipIds(state.playerMembershipIds())
                .gameStarted(state.gameStarted())
                .build();

        return new RecoveredAggregate(room, reconciledHost, game, false);
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
    AggregateStateImage.GameState toState(Game game) {
        if (game == null) {
            return null;
        }
        List<Player> playerList = game.getPlayers();
        List<AggregateStateImage.PlayerState> playerStates = playerList != null
                ? playerList.stream().filter(Objects::nonNull).map(this::toState).toList()
                : List.of();
        List<Player> activeList = game.getActivePlayers();
        List<String> activePlayerIds = activeList != null
                ? activeList.stream().filter(Objects::nonNull).map(Player::getPlayerId).toList()
                : List.of();
        return new AggregateStateImage.GameState(
            game.getGameId(), playerStates, activePlayerIds,
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
    AggregateStateImage.PlayerState toState(Player player) {
        if (player == null) {
            return null;
        }
        return new AggregateStateImage.PlayerState(
                player.getName(), player.getPlayerId(), cards(player.getHoleCards()), cards(player.getBestHand()),
                player.getHandRank(), player.getChips(), player.getCurrentBet(), player.getHasFolded(),
                player.getIsAllIn(), player.getIsOut(), player.getIsDisconnected(),
                player.getDisconnectDeadlineEpochMs(), player.getIsReadyForNextHand());
    }

    /**
     * Restores a Room from a persisted room snapshot.
     *
     * @param state the saved room snapshot
     * @return reconstructed room instance
     */
    Room restoreRoom(AggregateStateImage.RoomState state) {
        if (state == null) {
            return null;
        }
        Map<String, LocalDateTime> joinTimes = state.playersWithJoinTime() == null
                ? Map.of() : state.playersWithJoinTime();
        return Room.restoreBuilder()
                .roomId(state.roomId())
                .roomName(state.roomName())
                .hostName(state.originalHost())
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

    /**
     * Validates that the persisted terminal result matches the active game identity and contains
     * structurally sound winner, chip balance, and final state projection fields.
     *
     * @param expectedGameId the authoritative game identifier
     * @param state the persisted terminal result state image to validate
     * @throws PersistenceException if identity or required fields are missing or corrupt
     */
    private void validateTerminalResult(String expectedGameId, AggregateStateImage.TerminalResultState state) {
        if (state == null) return;
        final long jsMaxSafeInteger = 9_007_199_254_740_991L;
        final var finalState = state.finalState();
        if (!"GAME_END".equals(state.type())) {
            throw new PersistenceException("Retained terminal result has invalid type");
        }
        if (state.gameId() == null || !state.gameId().equals(expectedGameId)) {
            throw new PersistenceException("Retained terminal result gameId does not match game identity");
        }
        boolean isAbandoned = Boolean.TRUE.equals(state.abandoned()) || "INACTIVITY".equals(state.reason());
        if (isAbandoned) {
            if (state.winner() != null || state.winnerChips() != null) {
                throw new PersistenceException("Retained terminal result abandonment contains a winner");
            }
        } else {
            if (state.winner() == null || state.winner().trim().isEmpty()) {
                throw new PersistenceException("Retained terminal result missing winner name");
            }
            if (state.winnerChips() == null) {
                throw new PersistenceException("Retained terminal result missing winner chips");
            }
            if (state.winnerChips() < 0) {
                throw new PersistenceException("Retained terminal result has invalid negative winner chips");
            }
        }
        if (state.message() == null || state.message().trim().isEmpty()) {
            throw new PersistenceException("Retained terminal result missing message");
        }
        if (finalState == null) {
            throw new PersistenceException("Retained terminal result missing final state projection");
        }
        if (finalState.maxPlayers() < 2 || finalState.maxPlayers() > 10) {
            throw new PersistenceException("Retained terminal result final state has invalid max players capacity");
        }
        if (finalState.stateRevision() < 0 || finalState.stateRevision() > jsMaxSafeInteger ||
                finalState.handNumber() < 0 || finalState.handNumber() > jsMaxSafeInteger ||
                finalState.serverTimeEpochMs() < 0 || finalState.serverTimeEpochMs() > jsMaxSafeInteger) {
            throw new PersistenceException("Retained terminal result has invalid negative state revision");
        }
        if (finalState.players() == null || finalState.players().isEmpty()) {
            throw new PersistenceException("Retained terminal result missing final players projection");
        }
        if (finalState.players().size() > finalState.maxPlayers()) {
            throw new PersistenceException("Retained terminal result exceeds player capacity");
        }
        if (finalState.phase() == null || finalState.readyGateState() == null || finalState.pots() == null ||
                finalState.communityCards() == null || finalState.winners() == null || finalState.legalActions() == null) {
            throw new PersistenceException("Retained terminal result has incomplete final state");
        }
        if (finalState.pot() < 0 || finalState.currentBet() < 0 ||
                (finalState.uncalledAmount() != null && finalState.uncalledAmount() < 0) ||
                (finalState.carryOverAmount() != null && finalState.carryOverAmount() < 0) ||
                (finalState.minimumBetAmount() != null && finalState.minimumBetAmount() < 0) ||
                (finalState.minimumRaiseAmount() != null && finalState.minimumRaiseAmount() < 0) ||
                finalState.pots().stream().anyMatch(value -> value == null || value < 0) ||
                finalState.communityCards().size() > 5) {
            throw new PersistenceException("Retained terminal result has invalid final state bounds");
        }
        if (finalState.currentPlayerName() != null || finalState.currentPlayerId() != null ||
                !finalState.legalActions().isEmpty() || Boolean.TRUE.equals(finalState.isReadyCountdownActive()) ||
                finalState.readyCountdownDeadlineEpochMs() != null || !Boolean.FALSE.equals(finalState.isAutoAdvancing()) ||
                !Boolean.FALSE.equals(finalState.isReadyCountdownActive()) || !Boolean.FALSE.equals(finalState.claimWinAvailable()) ||
                finalState.claimWinPlayerName() != null || finalState.winningsPerPlayer() != null ||
                finalState.readyGateState() != ReadyGateState.CLOSED) {
            throw new PersistenceException("Retained terminal result contains live-game controls");
        }
        if (!validateCards(finalState.communityCards(), 5)) {
            throw new PersistenceException("Retained terminal result has invalid community cards");
        }
        if (isAbandoned ? !finalState.winners().isEmpty()
                : finalState.winners().size() != 1 || !state.winner().equals(finalState.winners().getFirst())) {
            throw new PersistenceException("Retained terminal result winner roster is incoherent");
        }

        Set<String> ids = new HashSet<>();
        Set<String> names = new HashSet<>();
        Set<String> allHoleCards = new HashSet<>();
        Set<String> seenCards = new HashSet<>();
        for (var card : finalState.communityCards()) seenCards.add(card.rank() + ":" + card.suit());
        AggregateStateImage.FinalPlayerState champion = null;
        int champions = 0;
        for (var player : finalState.players()) {
            if (player == null || blank(player.id()) || blank(player.name()) || player.chips() < 0 ||
                    player.currentBet() < 0 || player.status() == null ||
                    !Set.of("FOLDED", "OUT", "DISCONNECTED", "ACTIVE", "ALL_IN").contains(player.status()) ||
                    !ids.add(player.id()) || !names.add(player.name())) {
                throw new PersistenceException("Retained terminal result has invalid player identity or fields");
            }
            if (player.isCurrentPlayer() || player.chipsWon() != null) {
                throw new PersistenceException("Retained terminal result contains live player fields");
            }
            if (player.bestHand() != null && !validateCards(player.bestHand(), 5)) {
                throw new PersistenceException("Retained terminal result has invalid best-hand cards");
            }
            if (player.holeCards() != null && !player.holeCards().isEmpty() && player.holeCards().size() != 2) {
                throw new PersistenceException("Retained terminal result has invalid hole cards");
            }
            if (player.holeCards() != null && !validateCards(player.holeCards(), 2)) {
                throw new PersistenceException("Retained terminal result has invalid hole cards");
            }
            if (player.holeCards() != null) {
                for (var card : player.holeCards()) {
                    String identity = card.rank() + ":" + card.suit();
                    if (!allHoleCards.add(identity) || !seenCards.add(identity)) {
                        throw new PersistenceException("Retained terminal result contains duplicate hole cards");
                    }
                }
            }
            if (Boolean.TRUE.equals(player.isWinner())) {
                champions++;
                champion = player;
            } else if (player.isWinner() == null || player.isWinner()) {
                throw new PersistenceException("Retained terminal result has invalid champion flags");
            }
            if (player.disconnectDeadlineEpochMs() != null &&
                    (player.disconnectDeadlineEpochMs() < 0 || player.disconnectDeadlineEpochMs() > jsMaxSafeInteger)) {
                throw new PersistenceException("Retained terminal result has invalid player deadline");
            }
        }
        if (isAbandoned ? champions != 0
                : champions != 1 || champion == null || !state.winner().equals(champion.name()) ||
                !Objects.equals(champion.chips(), state.winnerChips())) {
            throw new PersistenceException("Retained terminal result champion does not match frozen players");
        }
    }

    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }

    private static boolean validateCards(List<AggregateStateImage.CardState> cards, int maxSize) {
        if (cards == null || cards.size() > maxSize) return false;
        Set<String> unique = new HashSet<>();
        for (var card : cards) {
            if (card == null || card.rank() == null || card.suit() == null ||
                    !unique.add(card.rank() + ":" + card.suit())) return false;
        }
        return true;
    }

    /**
     * Maps an in-memory {@link GameEndResponse} into a durable {@link AggregateStateImage.TerminalResultState}.
     *
     * @param response the game-end response to serialize
     * @return the persistent terminal result state image, or {@code null} if response is null
     */
    private AggregateStateImage.TerminalResultState toTerminalResultState(GameEndResponse response) {
        if (response == null) return null;
        return new AggregateStateImage.TerminalResultState(
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

    /**
     * Converts a public game state response into an immutable {@link AggregateStateImage.FinalGameState} image.
     *
     * @param finalState the public game state response representing the terminal game table
     * @return the persistent final game state snapshot image, or {@code null} if finalState is null
     */
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

    /**
     * Converts an individual public player state into an immutable {@link AggregateStateImage.FinalPlayerState} image.
     *
     * @param player the public player state to serialize
     * @return the persistent final player state image, or {@code null} if player is null
     */
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

    /**
     * Reconstitutes an in-memory {@link GameEndResponse} from a persistent {@link AggregateStateImage.TerminalResultState}.
     *
     * @param state the persistent terminal result state image
     * @return the restored game-end response, or {@code null} if state is null
     */
    private GameEndResponse fromTerminalResultState(AggregateStateImage.TerminalResultState state) {
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

    /**
     * Reconstructs a {@link PublicGameStateResponse} from a persisted {@link AggregateStateImage.FinalGameState}.
     *
     * @param state the persistent final game state snapshot image
     * @return the reconstructed public game state response, or {@code null} if state is null
     */
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

    /**
     * Reconstructs a {@link PublicPlayerState} from a persisted {@link AggregateStateImage.FinalPlayerState}.
     *
     * @param state the persistent final player state image
     * @return the reconstructed public player state, or {@code null} if state is null
     */
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

    private static AggregateStateImage.SettlementState settlementState(HandSettlementResult result) {
        if (result == null) return null;
        return new AggregateStateImage.SettlementState(result.contestedAwards(), result.refunds(),
                result.winnerIds(), result.remainder(), result.settled());
    }

    private static HandSettlementResult settlementResult(AggregateStateImage.SettlementState state) {
        if (state == null) return null;
        return new HandSettlementResult(state.contestedAwards(), state.refunds(),
                state.winnerIds(), state.remainder(), state.settled());
    }

    /**
     * Converts an individual domain {@link Card} into an immutable {@link AggregateStateImage.CardState} image.
     *
     * @param card the domain card to convert
     * @return the persistent card state image record, or {@code null} if card is null
     */
    private static AggregateStateImage.CardState toCardState(Card card) {
        if (card == null) return null;
        return new AggregateStateImage.CardState(card.rank(), card.suit());
    }

    /**
     * Reconstructs an individual domain {@link Card} from an immutable {@link AggregateStateImage.CardState} image.
     *
     * @param state the persistent card state image record
     * @return the reconstructed domain Card, or {@code null} if state is null
     */
    private static Card fromCardState(AggregateStateImage.CardState state) {
        if (state == null) return null;
        return new Card(state.rank(), state.suit());
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
        return cards.stream().filter(Objects::nonNull).map(AggregateSnapshotMapper::toCardState).toList();
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
        return cards.stream().map(AggregateSnapshotMapper::fromCardState).toList();
    }
}
