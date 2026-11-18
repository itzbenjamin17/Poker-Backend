package com.pokergame.service;

import static java.util.Objects.requireNonNull;

import com.pokergame.dto.response.ActionErrorResponse;
import com.pokergame.dto.response.GameEndResponse;
import com.pokergame.dto.response.PlayerNotificationResponse;
import com.pokergame.dto.response.PrivatePlayerState;
import com.pokergame.dto.response.PublicGameStateResponse;
import com.pokergame.dto.response.PublicPlayerState;
import com.pokergame.enums.GamePhase;
import com.pokergame.enums.HandRank;
import com.pokergame.enums.PlayerStatus;
import com.pokergame.enums.ReadyGateState;
import com.pokergame.enums.RejectedAction;
import com.pokergame.enums.ResponseMessage;
import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.exception.ResourceNotFoundException;
import com.pokergame.model.Card;
import com.pokergame.model.Game;
import com.pokergame.model.HandSettlementResult;
import com.pokergame.model.Player;
import com.pokergame.model.Room;
import com.pokergame.persistence.transaction.PostSaveActions;
import com.pokergame.security.PlayerPrincipal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * Builds client-facing game snapshots (public table state, private hole-card state, showdown
 * reveals, and final game-end summaries) and sends WebSocket updates after state changes save.
 */
@Service
public class GameStateService {
    private static final Logger logger = LoggerFactory.getLogger(GameStateService.class);
    private static final String PRIVATE_QUEUE = "/queue/private";

    /** Status-banner text shown to clients while an all-in hand auto-advances to showdown. */
    static final String AUTO_ADVANCE_MESSAGE = "Auto-advancing...";

    private final RoomService roomService;
    private final SimpMessagingTemplate messagingTemplate;
    private final Clock clock;

    public GameStateService(
            RoomService roomService,
            SimpMessagingTemplate messagingTemplate,
            Clock clock) {
        this.roomService = roomService;
        this.messagingTemplate = messagingTemplate;
        this.clock = clock;
    }

    /**
     * Builds the current public game-state snapshot for REST endpoints.
     *
     * @param game the active game instance
     * @return public game-state response for the requested game
     * @throws ResourceNotFoundException if the room for {@code game} does not exist
     */
    public PublicGameStateResponse getPublicGameStateSnapshot(Game game) {
        requireNonNull(game, "game must not be null");
        Room room = requireRoom(game.getGameId());
        return buildLiveGameState(game, room.getInactivityDeadlineEpochMs());
    }

    /**
     * Builds the private game-state snapshot containing only {@code playerName}'s hole cards.
     *
     * @param game       the active game instance
     * @param playerName the authenticated player's name
     * @return private state containing the player's hole cards
     * @throws ResourceNotFoundException if the player is not in {@code game}
     */
    public PrivatePlayerState getPrivatePlayerStateSnapshot(Game game, String playerName) {
        requireNonNull(game, "game must not be null");
        requireNonNull(playerName, "playerName must not be null");

        Player player = game.findPlayerByName(playerName);
        if (player == null) {
            throw new ResourceNotFoundException("Player not found in game");
        }

        return buildPrivatePlayerState(game, player);
    }

    /**
     * Broadcasts the public table state to the game topic and each player's private hole cards
     * to their private queue after save.
     *
     * @param game the active game instance
     * @throws ResourceNotFoundException if the room for {@code game} does not exist
     */
    public void broadcastGameState(Game game) {
        requireNonNull(game, "game must not be null");
        String gameId = game.getGameId();

        logger.debug("Broadcasting game state for game {}", gameId);

        Room room = requireRoom(gameId);

        game.incrementStateRevision();
        PublicGameStateResponse publicResponse = buildLiveGameState(game, room.getInactivityDeadlineEpochMs());
        Map<String, PrivatePlayerState> playerPrivateStates = new HashMap<>();
        for (Player player : game.getPlayers()) {
            playerPrivateStates.put(player.getName(), buildPrivatePlayerState(game, player));
        }

        sendAfterSave("/game/" + gameId, publicResponse);

        playerPrivateStates.forEach((playerName, privateState) ->
                sendToPlayerAfterSave(gameId, playerName, privateState));
    }

    /**
     * Broadcasts settled hand showdown results to all players after save, revealing winning hand
     * ranks, awards, and hole cards for actual showdowns while clearing current-turn pointers.
     *
     * @param game the settled game instance containing showdown results
     * @throws IllegalStateException if the current hand has not been settled yet
     */
    public void broadcastShowdownResults(Game game) {
        requireNonNull(game, "game must not be null");
        String gameId = game.getGameId();

        HandSettlementResult settlement = game.getLastSettlementResult();
        if (settlement == null) {
            throw new IllegalStateException("Cannot broadcast showdown results for unsettled game " + gameId);
        }

        game.incrementStateRevision();

        // The showdown view sets no current player, so clients do not briefly show Fold/Check
        // controls before the next hand or game-end update arrives.
        Set<String> winnerIds = settlement.winnerIds();
        Map<String, Integer> contestedAwards = settlement.contestedAwards();

        List<Player> winningPlayers = game.getPlayers().stream()
                .filter(player -> winnerIds.contains(player.getPlayerId()))
                .toList();
        List<String> winnerNames = winningPlayers.stream().map(Player::getName).toList();
        Integer equalWinnerAward = computeEqualWinnerAward(winnerIds, contestedAwards);

        boolean isActualShowdown = game.wasContestedShowdown();
        String smallBlindPlayerId = game.getSmallBlindPlayerId();
        String bigBlindPlayerId = game.getBigBlindPlayerId();

        List<PublicPlayerState> playersList = game.getPlayers().stream().map(player -> {
            boolean isWinner = winnerIds.contains(player.getPlayerId());
            boolean isActive = player.isInHand();
            return toPublicPlayerState(
                    player,
                    false,
                    smallBlindPlayerId,
                    bigBlindPlayerId,
                    isActive ? player.getHandRank() : null,
                    isActive ? player.getBestHand() : List.of(),
                    isWinner,
                    contestedAwards.getOrDefault(player.getPlayerId(), 0),
                    (isActive && isActualShowdown) ? player.getHoleCards() : null);
        }).toList();

        PublicGameStateResponse showdownResponse = PublicGameStateBuilder.forGame(game, clock.millis())
                .players(playersList)
                .winners(winnerNames)
                .winningsPerPlayer(equalWinnerAward)
                .readyCountdown(game.isReadyCountdownActive(), game.getReadyCountdownDeadlineEpochMs())
                .handSettled(true)
                .carryOverAmount(settlement.remainder())
                .build();

        sendAfterSave("/game/" + gameId, showdownResponse);

        logger.info("Broadcasted showdown results for game {} with {} winner(s): {}",
                gameId, winnerNames.size(), winnerNames);
        logger.debug("Showdown game state - winners: {}, winnerCount: {}, equalWinnerAward: {}",
                winnerNames, winnerNames.size(), equalWinnerAward);
    }

    /**
     * Broadcasts public game state flagged for all-in auto-advance with a status message.
     *
     * @param game    the active game instance
     * @param message the status banner message to display while auto-advancing
     */
    public void broadcastGameStateWithAutoAdvance(Game game, String message) {
        requireNonNull(game, "game must not be null");
        String gameId = game.getGameId();

        game.incrementStateRevision();

        String smallBlindPlayerId = game.getSmallBlindPlayerId();
        String bigBlindPlayerId = game.getBigBlindPlayerId();

        List<PublicPlayerState> playersList = game.getPlayers().stream()
                .map(player -> toPublicPlayerState(
                        player,
                        false,
                        smallBlindPlayerId,
                        bigBlindPlayerId,
                        player.getHandRank(),
                        List.of(),
                        null,
                        null,
                        null))
                .toList();

        PublicGameStateResponse autoAdvanceResponse = PublicGameStateBuilder.forGame(game, clock.millis())
                .players(playersList)
                .autoAdvance(true, message)
                .readyCountdown(game.isReadyCountdownActive(), game.getReadyCountdownDeadlineEpochMs())
                .legalActions(List.of())
                .build();

        logger.info("Broadcasting auto-advance state for game {}: {}", gameId, message);
        sendAfterSave("/game/" + gameId, autoAdvanceResponse);
    }

    /**
     * Broadcasts an immediate notice that all-in auto-advance to showdown has begun.
     *
     * @param game the active game instance
     */
    public void broadcastAutoAdvanceNotification(Game game) {
        requireNonNull(game, "game must not be null");

        logger.info("Broadcasting immediate auto-advance state for game {}", game.getGameId());
        broadcastGameStateWithAutoAdvance(game,
                "No further betting actions are possible. Auto-advancing to showdown...");
    }

    /**
     * Broadcasts a notice that all community cards for auto-advance have been dealt.
     *
     * @param game the active game instance
     */
    public void broadcastAutoAdvanceComplete(Game game) {
        requireNonNull(game, "game must not be null");
        String gameId = game.getGameId();

        logger.info("Broadcasting auto-advance complete for game {}: {}", gameId, game.getCommunityCards());
        sendAfterSave("/game/" + gameId,
                new PlayerNotificationResponse(ResponseMessage.AUTO_ADVANCE_COMPLETE, "", null, gameId));
    }

    /**
     * Sends a private notification to the specified player's active WebSocket session after save.
     *
     * @param gameId     the unique ID of the game
     * @param playerName the recipient player's name
     * @param message    the notification message text
     * @param type       the notification category
     */
    public void sendPrivatePlayerNotification(String gameId, String playerName, String message, ResponseMessage type) {
        PlayerNotificationResponse notification = new PlayerNotificationResponse(
                type, message, playerName, gameId);
        sendToPlayerAfterSave(gameId, playerName, notification);
    }

    /**
     * Sends a private notification directly to the authenticated session in {@code origin},
     * even if the display name has been claimed by a different session later.
     *
     * @param origin  the principal bound to the originating WebSocket session
     * @param message the notification message text
     * @param type    the notification category
     */
    public void sendPrivatePlayerNotification(PlayerPrincipal origin, String message, ResponseMessage type) {
        sendToPrincipalAfterSave(origin,
                new PlayerNotificationResponse(type, message, origin.playerName(), origin.roomId()));
    }

    /**
     * Sends a private action-error response for a rejected gameplay or readiness action.
     *
     * @param origin  the principal bound to the originating WebSocket session
     * @param code    application error code
     * @param action  the rejected action descriptor
     * @param message explanation shown to the player
     */
    public void sendActionError(PlayerPrincipal origin, String code, RejectedAction action, String message) {
        sendToPrincipalAfterSave(origin, new ActionErrorResponse(code, action, message));
    }

    /**
     * Increments the game's state revision and builds the final game-end payload for a won game.
     *
     * @param game      the completed game instance
     * @param winner    the winning player
     * @param isForfeit {@code true} if the game ended by forfeit
     * @return the final {@link GameEndResponse}
     */
    public GameEndResponse advanceRevisionAndBuildGameEnd(Game game, Player winner, boolean isForfeit) {
        requireNonNull(game, "game must not be null");
        requireNonNull(winner, "winner must not be null");
        game.incrementStateRevision();
        return buildGameEndResponse(game, winner, isForfeit);
    }

    /**
     * Increments the game's state revision and builds the final payload for a game closed due
     * to inactivity.
     *
     * @param game the abandoned game instance
     * @return the abandonment {@link GameEndResponse}
     */
    public GameEndResponse advanceRevisionAndBuildAbandonment(Game game) {
        requireNonNull(game, "game must not be null");
        game.incrementStateRevision();
        return buildAbandonmentResponse(game);
    }

    /**
     * Builds a read-only final game-end response without mutating {@code game}'s state revision.
     *
     * @param game      the completed game instance
     * @param winner    the winning player
     * @param isForfeit {@code true} if the game ended by forfeit
     * @return the final {@link GameEndResponse}
     */
    public GameEndResponse buildGameEndResponse(Game game, Player winner, boolean isForfeit) {
        requireNonNull(game, "game must not be null");
        requireNonNull(winner, "winner must not be null");
        String gameId = game.getGameId();
        String message = winner.getName() + " wins the game with " + winner.getChips() + " chips!";
        PublicGameStateResponse finalState = buildCompletedGameState(game, winner);
        return new GameEndResponse(
                ResponseMessage.GAME_END.getMessage(), gameId, winner.getName(),
                winner.getChips(), isForfeit, message, finalState);
    }

    /**
     * Builds a read-only inactivity abandonment response without mutating {@code game}'s state revision.
     *
     * @param game the abandoned game instance
     * @return the abandonment {@link GameEndResponse}
     */
    public GameEndResponse buildAbandonmentResponse(Game game) {
        requireNonNull(game, "game must not be null");
        String gameId = game.getGameId();
        String message = "Game abandoned due to inactivity.";
        PublicGameStateResponse finalState = buildAbandonedGameState(game);
        return new GameEndResponse(
                ResponseMessage.GAME_END.getMessage(), gameId, null,
                null, false, true, "INACTIVITY", message, finalState);
    }

    /**
     * Sends a completed {@link GameEndResponse} to the game topic after save.
     *
     * @param response the final game-end payload, ignored if {@code null}
     */
    public void sendGameEnd(GameEndResponse response) {
        if (response != null) {
            sendAfterSave("/game/" + response.gameId(), response);
        }
    }

    private PublicGameStateResponse buildAbandonedGameState(Game game) {
        String smallBlindPlayerId = game.getSmallBlindPlayerId();
        String bigBlindPlayerId = game.getBigBlindPlayerId();

        List<PublicPlayerState> playerStates = game.getPlayers().stream()
                .map(player -> toPublicPlayerState(
                        player,
                        false,
                        smallBlindPlayerId,
                        bigBlindPlayerId,
                        null,
                        List.of(),
                        false,
                        null,
                        null))
                .toList();

        return PublicGameStateBuilder.forGame(game, clock.millis())
                .players(playerStates)
                .winners(List.of())
                .autoAdvance(false, null)
                .readyCountdown(false, null)
                .claimWin(false, null)
                .handSettled(game.isHandSettled())
                .legalActions(List.of())
                .readyGateState(ReadyGateState.CLOSED)
                .build();
    }

    private PublicGameStateResponse buildCompletedGameState(Game game, Player winner) {
        boolean handSettled = game.isHandSettled();
        boolean revealHoleCards = game.wasContestedShowdown();

        String smallBlindPlayerId = game.getSmallBlindPlayerId();
        String bigBlindPlayerId = game.getBigBlindPlayerId();

        List<PublicPlayerState> playerStates = game.getPlayers().stream().map(player -> {
            boolean isWinner = winner.equals(player);
            boolean isActive = player.isInHand();
            return toPublicPlayerState(
                    player,
                    false,
                    smallBlindPlayerId,
                    bigBlindPlayerId,
                    isActive ? player.getHandRank() : null,
                    isActive ? player.getBestHand() : List.of(),
                    isWinner,
                    null,
                    (revealHoleCards && isActive) ? player.getHoleCards() : null);
        }).toList();

        List<String> winnerNames = List.of(winner.getName());

        return PublicGameStateBuilder.forGame(game, clock.millis())
                .players(playerStates)
                .winners(winnerNames)
                .autoAdvance(false, null)
                .readyCountdown(false, null)
                .claimWin(false, null)
                .handSettled(handSettled)
                .legalActions(List.of())
                .readyGateState(ReadyGateState.CLOSED)
                .build();
    }

    private Room requireRoom(String gameId) {
        Room room = roomService.getRoom(gameId);
        if (room == null) {
            logger.warn("Game state build failed: room not found for gameId {}", gameId);
            throw new ResourceNotFoundException("Room not found");
        }
        return room;
    }

    private PublicGameStateResponse buildLiveGameState(Game game, Long inactivityDeadlineEpochMs) {
        boolean autoAdvancing = game.isAutoAdvancing();
        HandSettlementResult lastSettlement = game.getLastSettlementResult();
        boolean inShowdownOrCountdown = game.getCurrentPhase() == GamePhase.SHOWDOWN || game.isReadyCountdownActive();
        boolean handSettled = game.isHandSettled();
        Set<String> settledWinnerIds = handSettled ? lastSettlement.winnerIds() : Set.of();
        Map<String, Integer> settledAwards = handSettled ? lastSettlement.contestedAwards() : Map.of();
        boolean revealHoleCards = game.wasContestedShowdown();

        Player currentPlayer = !inShowdownOrCountdown && !autoAdvancing && !handSettled && !game.getActivePlayers().isEmpty()
                ? game.getCurrentPlayer()
                : null;
        String smallBlindPlayerId = game.getSmallBlindPlayerId();
        String bigBlindPlayerId = game.getBigBlindPlayerId();
        if (currentPlayer != null) {
            logger.debug("Building game state - current player: {} (ID: {})",
                    currentPlayer.getName(), currentPlayer.getPlayerId());
        } else {
            logger.debug("Building game state - no active players found");
        }

        List<PublicPlayerState> playerStates = new ArrayList<>();
        for (Player player : game.getPlayers()) {
            boolean isActive = player.isInHand();
            Boolean isWinner = handSettled ? settledWinnerIds.contains(player.getPlayerId()) : null;
            Integer chipsWon = handSettled
                    ? settledAwards.getOrDefault(player.getPlayerId(), 0)
                    : null;
            playerStates.add(toPublicPlayerState(
                    player,
                    player.equals(currentPlayer),
                    smallBlindPlayerId,
                    bigBlindPlayerId,
                    handSettled && !settledWinnerIds.isEmpty() && isActive ? player.getHandRank() : null,
                    handSettled && !settledWinnerIds.isEmpty() && isActive ? player.getBestHand() : null,
                    isWinner,
                    chipsWon,
                    revealHoleCards && isActive ? player.getHoleCards() : null));
        }

        String claimWinPlayerName = computeClaimWinPlayerName(game);
        boolean claimWinAvailable = claimWinPlayerName != null;
        List<String> winnerNames = handSettled
                ? game.getPlayers().stream()
                        .filter(player -> settledWinnerIds.contains(player.getPlayerId()))
                        .map(Player::getName)
                        .toList()
                : null;
        Integer winningsPerPlayer = handSettled
                ? computeEqualWinnerAward(settledWinnerIds, settledAwards)
                : null;

        return PublicGameStateBuilder.forGame(game, clock.millis())
                .players(playerStates)
                .currentPlayer(
                        currentPlayer != null ? currentPlayer.getName() : null,
                        currentPlayer != null ? currentPlayer.getPlayerId() : null)
                .winners(winnerNames)
                .winningsPerPlayer(winningsPerPlayer)
                .autoAdvance(autoAdvancing, autoAdvancing ? AUTO_ADVANCE_MESSAGE : null)
                .readyCountdown(game.isReadyCountdownActive(), game.getReadyCountdownDeadlineEpochMs())
                .claimWin(claimWinAvailable, claimWinPlayerName)
                .handSettled(handSettled)
                .carryOverAmount(handSettled ? lastSettlement.remainder() : null)
                .legalActions(currentPlayer == null ? List.of() : game.getLegalActionsFor(currentPlayer))
                .minimumBetAmount(currentPlayer == null ? null : game.getBigBlind())
                .minimumRaiseAmount(currentPlayer == null ? null : game.getMinimumRaiseAmount(currentPlayer))
                .turnDeadlineEpochMs(
                        currentPlayer == null ? null : game.getScheduledTaskDeadline(ScheduledGameTask.TURN_TIMEOUT))
                .inactivityDeadlineEpochMs(inactivityDeadlineEpochMs)
                .build();
    }

    private Integer computeEqualWinnerAward(Set<String> winnerIds, Map<String, Integer> contestedAwards) {
        if (winnerIds == null || winnerIds.isEmpty()) {
            return null;
        }
        List<Integer> awards = winnerIds.stream()
                .map(id -> contestedAwards.getOrDefault(id, 0))
                .toList();
        return awards.stream().distinct().count() == 1 ? awards.getFirst() : null;
    }

    private PublicPlayerState toPublicPlayerState(
            Player player,
            boolean isCurrentPlayer,
            String smallBlindPlayerId,
            String bigBlindPlayerId,
            HandRank handRank,
            List<Card> bestHand,
            Boolean isWinner,
            Integer chipsWon,
            List<Card> holeCards) {
        return new PublicPlayerState(
                player.getPlayerId(),
                player.getName(),
                player.getChips(),
                player.getCurrentBet(),
                resolvePlayerStatus(player),
                player.isAllIn(),
                isCurrentPlayer,
                player.hasFolded(),
                player.getPlayerId().equals(smallBlindPlayerId),
                player.getPlayerId().equals(bigBlindPlayerId),
                handRank,
                bestHand,
                isWinner,
                chipsWon,
                holeCards,
                player.getDisconnectDeadlineEpochMs(),
                player.isReadyForNextHand());
    }

    private String resolvePlayerStatus(Player player) {
        if (player.isOut()) {
            return PlayerStatus.OUT.getStatus();
        }
        if (player.hasFolded()) {
            return PlayerStatus.FOLDED.getStatus();
        }
        if (player.isDisconnected()) {
            return PlayerStatus.DISCONNECTED.getStatus();
        }
        if (player.isAllIn()) {
            return PlayerStatus.ALL_IN.getStatus();
        }
        return PlayerStatus.ACTIVE.getStatus();
    }

    private String computeClaimWinPlayerName(Game game) {
        return game.disconnectWinClaimant()
                .map(Player::getName)
                .orElse(null);
    }

    private PrivatePlayerState buildPrivatePlayerState(Game game, Player player) {
        List<Card> holeCards = player.getHoleCards() == null
                ? List.of()
                : new ArrayList<>(player.getHoleCards());
        return new PrivatePlayerState(player.getPlayerId(), game.getHandNumber(), holeCards);
    }

    private void sendAfterSave(String destination, Object payload) {
        PostSaveActions.runAfterSave(() -> messagingTemplate.convertAndSend(destination, payload));
    }

    private void sendToPlayerAfterSave(String gameId, String playerName, Object payload) {
        Room room = roomService.getRoom(gameId);
        if (room == null) {
            return;
        }
        // Capture membership ID before queuing so a replacement player with the same name never receives it.
        // A missing or blank membership ID is dropped by sendToPrincipalAfterSave.
        String membershipId = room.getMembershipId(playerName);
        sendToPrincipalAfterSave(new PlayerPrincipal(playerName, gameId, membershipId), payload);
    }

    private void sendToPrincipalAfterSave(PlayerPrincipal origin, Object payload) {
        if (origin.membershipId() == null || origin.membershipId().isBlank()) {
            return;
        }
        String user = origin.getName();
        PostSaveActions.runAfterSave(() -> messagingTemplate.convertAndSendToUser(user, PRIVATE_QUEUE, payload));
    }
}
