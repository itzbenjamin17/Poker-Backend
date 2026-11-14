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
import com.pokergame.enums.PlayerAction;
import com.pokergame.enums.PlayerStatus;
import com.pokergame.enums.ReadyGateState;
import com.pokergame.enums.RejectedAction;
import com.pokergame.enums.ResponseMessage;
import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.exception.BadRequestException;
import com.pokergame.exception.ResourceNotFoundException;
import com.pokergame.model.Card;
import com.pokergame.model.Game;
import com.pokergame.model.HandSettlementResult;
import com.pokergame.model.Player;
import com.pokergame.model.Room;
import com.pokergame.model.TournamentEligibility;
import com.pokergame.persistence.transaction.PostSaveActions;
import com.pokergame.security.PlayerPrincipal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
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
     * @param gameId the unique ID of the game
     * @param game   the active game instance
     * @return public game-state response for the requested game
     * @throws ResourceNotFoundException if the backing room does not exist
     */
    public PublicGameStateResponse getPublicGameStateSnapshot(String gameId, Game game) {
        Room room = roomService.getRoom(gameId);
        if (room == null) {
            logger.warn("Game state build failed: room not found for gameId {}", gameId);
            throw new ResourceNotFoundException("Room not found");
        }
        return buildLiveGameState(gameId, game, room);
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
        Player player = game.getPlayers().stream()
                .filter(p -> p.getName().equals(playerName))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Player not found in game"));

        return buildPrivatePlayerState(game, player);
    }

    /**
     * Broadcasts the public table state to the game topic and each player's private hole cards
     * to their private queue after save.
     *
     * @param gameId the unique ID of the game
     * @param game   the active game instance
     * @throws BadRequestException       if {@code game} is {@code null}
     * @throws ResourceNotFoundException if the backing room does not exist
     */
    public void broadcastGameState(String gameId, Game game) {
        if (game == null) {
            logger.warn("Cannot broadcast game state - game {} not found", gameId);
            throw new BadRequestException("Trying to broadcast state for a non existent room: " + gameId);
        }

        logger.debug("Broadcasting game state for game {}", gameId);

        Room room = roomService.getRoom(gameId);
        if (room == null) {
            logger.warn("Game state build failed: room not found for gameId {}", gameId);
            throw new ResourceNotFoundException("Room not found");
        }

        game.incrementStateRevision();
        PublicGameStateResponse publicResponse = buildLiveGameState(gameId, game, room);
        Map<String, PrivatePlayerState> playerPrivateStates = new HashMap<>();
        for (Player player : game.getPlayers()) {
            playerPrivateStates.put(player.getName(), buildPrivatePlayerState(game, player));
        }

        sendAfterSave("/game/" + gameId, publicResponse);

        playerPrivateStates.forEach((playerName, privateState) ->
                sendToPlayerAfterSave(gameId, playerName, "/queue/private", privateState));
    }

    /**
     * Broadcasts hand showdown results to all players after save, revealing winning hand ranks,
     * awards, and hole cards for actual showdowns while clearing current-turn pointers.
     *
     * @param gameId            the unique ID of the game
     * @param game              the game instance containing showdown state
     * @param winners           the winning players for the hand
     * @param winningsPerPlayer the chip award per winner for equal splits
     */
    public void broadcastShowdownResults(String gameId, Game game, List<Player> winners, int winningsPerPlayer) {
        if (game == null) {
            logger.warn("Cannot broadcast showdown - game {} not found", gameId);
            return;
        }

        Room room = roomService.getRoom(gameId);
        int maxPlayers = room != null ? room.getMaxPlayers() : 0;

        game.incrementStateRevision();

        // Clear turn pointers at showdown so clients do not briefly show Fold/Check controls
        // before the next hand or game-end update arrives.
        String currentPlayerName = null;
        String currentPlayerId = null;

        HandSettlementResult settlement = game.getLastSettlementResult();
        boolean settled = settlement != null && settlement.settled();
        Set<String> winnerIds;
        Map<String, Integer> contestedAwards;
        HandSettlementResult effectiveSettlement;
        if (settled) {
            winnerIds = settlement.winnerIds();
            contestedAwards = settlement.contestedAwards();
            effectiveSettlement = settlement;
        } else {
            winnerIds = winners.stream().map(Player::getPlayerId).collect(Collectors.toSet());
            contestedAwards = winners.stream().collect(Collectors.toMap(
                    Player::getPlayerId,
                    ignored -> winningsPerPlayer,
                    (left, right) -> left));
            effectiveSettlement = new HandSettlementResult(contestedAwards, Map.of(), winnerIds, 0, true);
        }

        List<Player> projectedWinners = game.getPlayers().stream()
                .filter(player -> winnerIds.contains(player.getPlayerId()))
                .toList();
        List<String> winnerNames = projectedWinners.stream().map(Player::getName).toList();
        Integer legacyWinnings = computeEqualWinnerAward(winnerIds, contestedAwards);

        boolean isActualShowdown = projectedWinners.stream()
                .anyMatch(w -> w.getHandRank() != null && w.getHandRank() != HandRank.NO_HAND);
        String smallBlindPlayerId = game.getSmallBlindPlayerId();
        String bigBlindPlayerId = game.getBigBlindPlayerId();

        List<PublicPlayerState> playersList = game.getPlayers().stream().map(player -> {
            boolean isWinner = winnerIds.contains(player.getPlayerId());
            boolean isActive = !player.getHasFolded() && !player.getIsOut();
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

        PublicGameStateResponse showdownResponse = new PublicGameStateResponse(
                maxPlayers,
                game.getPot(),
                game.getPotBreakdown(),
                game.getUncalledAmount(),
                game.getCurrentPhase(),
                game.getCurrentHighestBet(),
                game.getCommunityCards(),
                playersList,
                currentPlayerName,
                currentPlayerId,
                winnerNames,
                legacyWinnings,
                null,
                null,
                game.isReadyCountdownActive(),
                game.getReadyCountdownDeadlineEpochMs(),
                null,
                null,
                settled,
                settled ? effectiveSettlement.remainder() : null,
                null,
                null,
                null,
                game.getHandNumber(),
                game.getReadyGateState(),
                clock.millis(),
                game.getStateRevision());

        sendAfterSave("/game/" + gameId, showdownResponse);

        logger.info("Broadcasted showdown results for game {} with {} winner(s): {}",
                gameId, winnerNames.size(), winnerNames);
        logger.debug("Showdown game state - winners: {}, winnerCount: {}, winnings per player: {}",
                winnerNames, winnerNames.size(), winningsPerPlayer);
    }

    /**
     * Broadcasts public game state flagged for all-in auto-advance with a status message.
     *
     * @param gameId  the unique ID of the game
     * @param game    the active game instance
     * @param message the status banner message to display while auto-advancing
     */
    public void broadcastGameStateWithAutoAdvance(String gameId, Game game, String message) {
        if (game == null) {
            logger.warn("Cannot broadcast auto-advance state - game {} not found", gameId);
            return;
        }

        Room room = roomService.getRoom(gameId);
        int maxPlayers = room != null ? room.getMaxPlayers() : 0;

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

        PublicGameStateResponse autoAdvanceResponse = new PublicGameStateResponse(
                maxPlayers,
                game.getPot(),
                game.getPotBreakdown(),
                game.getUncalledAmount(),
                game.getCurrentPhase(),
                game.getCurrentHighestBet(),
                game.getCommunityCards(),
                playersList,
                null,
                null,
                null,
                null,
                true,
                message,
                game.isReadyCountdownActive(),
                game.getReadyCountdownDeadlineEpochMs(),
                null,
                null,
                null,
                null,
                List.of(),
                null,
                null,
                game.getHandNumber(),
                game.getReadyGateState(),
                clock.millis(),
                game.getStateRevision());

        logger.info("Broadcasting auto-advance state for game {}: {}", gameId, message);
        sendAfterSave("/game/" + gameId, autoAdvanceResponse);
    }

    /**
     * Broadcasts an immediate notice that all-in auto-advance to showdown has begun.
     *
     * @param gameId the unique ID of the game
     * @param game   the active game instance
     */
    public void broadcastAutoAdvanceNotification(String gameId, Game game) {
        if (game == null) {
            logger.warn("Cannot broadcast auto-advance notification - game {} not found", gameId);
            return;
        }

        logger.info("Broadcasting immediate auto-advance state for game {}", gameId);
        broadcastGameStateWithAutoAdvance(gameId, game,
                "No further betting actions are possible. Auto-advancing to showdown...");
    }

    /**
     * Broadcasts a notice that all community cards for auto-advance have been dealt.
     *
     * @param gameId the unique ID of the game
     * @param game   the active game instance
     */
    public void broadcastAutoAdvanceComplete(String gameId, Game game) {
        if (game == null) {
            logger.warn("Cannot broadcast auto-advance complete - game {} not found", gameId);
            return;
        }

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
        sendToPlayerAfterSave(gameId, playerName, "/queue/private", notification);
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
        sendToPrincipalAfterSave(origin, "/queue/private",
                new PlayerNotificationResponse(type, message, origin.playerName(), origin.roomId()));
    }

    /**
     * Sends a private action-error response for a rejected gameplay action.
     *
     * @param origin  the principal bound to the originating WebSocket session
     * @param code    application error code
     * @param action  the poker action that was rejected
     * @param message explanation shown to the player
     */
    public void sendActionError(PlayerPrincipal origin, String code, PlayerAction action, String message) {
        sendActionError(origin, code, RejectedAction.from(action), message);
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
        sendToPrincipalAfterSave(origin, "/queue/private", new ActionErrorResponse(code, action, message));
    }

    /**
     * Increments the game's state revision and builds the final game-end payload for a won game.
     *
     * @param gameId     the unique ID of the game
     * @param game       the completed game instance
     * @param winner     the winning player, or {@code null} if unavailable
     * @param isForfeit  {@code true} if the game ended by forfeit
     * @param maxPlayers configured maximum seats for the room
     * @return the final {@link GameEndResponse}, or {@code null} when {@code winner} is {@code null}
     */
    public GameEndResponse recordGameEnd(String gameId, Game game, Player winner, boolean isForfeit, int maxPlayers) {
        if (winner == null) {
            return null;
        }
        if (game != null) {
            game.incrementStateRevision();
        }
        return buildGameEndResponse(gameId, game, winner, isForfeit, maxPlayers);
    }

    /**
     * Increments the game's state revision and builds the final payload for a game closed due
     * to inactivity.
     *
     * @param gameId     the unique ID of the game
     * @param game       the abandoned game instance
     * @param maxPlayers configured maximum seats for the room
     * @return the abandonment {@link GameEndResponse}
     */
    public GameEndResponse recordAbandonment(String gameId, Game game, int maxPlayers) {
        if (game != null) {
            game.incrementStateRevision();
        }
        return buildAbandonmentResponse(gameId, game, maxPlayers);
    }

    /**
     * Builds a read-only final game-end response without mutating {@code game}'s state revision.
     *
     * @param gameId     the unique ID of the game
     * @param game       the completed game instance
     * @param winner     the winning player, or {@code null} if unavailable
     * @param isForfeit  {@code true} if the game ended by forfeit
     * @param maxPlayers configured maximum seats for the room
     * @return the final {@link GameEndResponse}, or {@code null} when {@code winner} is {@code null}
     */
    public GameEndResponse buildGameEndResponse(
            String gameId, Game game, Player winner, boolean isForfeit, int maxPlayers) {
        if (winner == null) {
            return null;
        }
        String message = "🏆 " + winner.getName() + " wins the game with " + winner.getChips() + " chips!";
        PublicGameStateResponse finalState = game == null
                ? null
                : buildCompletedGameState(gameId, game, winner, isForfeit, maxPlayers);
        return new GameEndResponse(
                ResponseMessage.GAME_END.getMessage(), gameId, winner.getName(),
                winner.getChips(), isForfeit, message, finalState);
    }

    /**
     * Builds a read-only inactivity abandonment response without mutating {@code game}'s state revision.
     *
     * @param gameId     the unique ID of the game
     * @param game       the abandoned game instance
     * @param maxPlayers configured maximum seats for the room
     * @return the abandonment {@link GameEndResponse}
     */
    public GameEndResponse buildAbandonmentResponse(String gameId, Game game, int maxPlayers) {
        String message = "Game abandoned due to inactivity.";
        PublicGameStateResponse finalState = game == null ? null : buildAbandonedGameState(gameId, game, maxPlayers);
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

    private int resolveMaxPlayers(String gameId, Game game, int maxPlayers) {
        if (maxPlayers >= 2 && maxPlayers <= 10) {
            return maxPlayers;
        }
        Room room = roomService.getRoom(gameId);
        if (room != null && room.getMaxPlayers() >= 2 && room.getMaxPlayers() <= 10) {
            return room.getMaxPlayers();
        }
        int retainedPlayerCount = game != null && game.getPlayers() != null ? game.getPlayers().size() : 2;
        return Math.max(2, Math.min(10, retainedPlayerCount));
    }

    private PublicGameStateResponse buildAbandonedGameState(String gameId, Game game, int maxPlayers) {
        requireNonNull(game, "game must not be null");
        int resolvedMaxPlayers = resolveMaxPlayers(gameId, game, maxPlayers);

        String smallBlindPlayerId = game.getSmallBlindPlayerId();
        String bigBlindPlayerId = game.getBigBlindPlayerId();

        List<PublicPlayerState> playersList = game.getPlayers().stream()
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

        boolean handSettled = game.getLastSettlementResult() != null && game.getLastSettlementResult().settled();

        return new PublicGameStateResponse(
                resolvedMaxPlayers,
                game.getPot(),
                game.getPotBreakdown(),
                game.getUncalledAmount(),
                game.getCurrentPhase(),
                game.getCurrentHighestBet(),
                game.getCommunityCards(),
                playersList,
                null,
                null,
                List.of(),
                null,
                false,
                null,
                false,
                null,
                false,
                null,
                handSettled,
                null,
                List.of(),
                null,
                null,
                game.getHandNumber(),
                ReadyGateState.CLOSED,
                clock.millis(),
                game.getStateRevision(),
                null,
                null);
    }

    private PublicGameStateResponse buildCompletedGameState(
            String gameId, Game game, Player winner, boolean isForfeit, int maxPlayers) {
        requireNonNull(game, "game must not be null");
        int resolvedMaxPlayers = resolveMaxPlayers(gameId, game, maxPlayers);

        boolean contestedShowdown = game.getCurrentPhase() == GamePhase.SHOWDOWN
                && game.getLastSettlementResult() != null && game.getLastSettlementResult().settled()
                && game.getActivePlayers().stream()
                        .filter(player -> !player.getHasFolded() && !player.getIsOut())
                        .count() > 1;
        boolean revealHoleCards = !isForfeit && contestedShowdown;

        String smallBlindPlayerId = game.getSmallBlindPlayerId();
        String bigBlindPlayerId = game.getBigBlindPlayerId();

        List<PublicPlayerState> playersList = game.getPlayers().stream().map(player -> {
            boolean isWinner = winner != null && winner.equals(player);
            boolean isActive = !player.getHasFolded() && !player.getIsOut();
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

        List<String> winnerNames = winner != null ? List.of(winner.getName()) : List.of();
        boolean handSettled = game.getLastSettlementResult() != null && game.getLastSettlementResult().settled();

        return new PublicGameStateResponse(
                resolvedMaxPlayers,
                game.getPot(),
                game.getPotBreakdown(),
                game.getUncalledAmount(),
                game.getCurrentPhase(),
                game.getCurrentHighestBet(),
                game.getCommunityCards(),
                playersList,
                null,
                null,
                winnerNames,
                null,
                false,
                null,
                false,
                null,
                false,
                null,
                handSettled,
                null,
                List.of(),
                null,
                null,
                game.getHandNumber(),
                ReadyGateState.CLOSED,
                clock.millis(),
                game.getStateRevision());
    }

    private PublicGameStateResponse buildLiveGameState(String gameId, Game game, Room room) {
        if (room == null) {
            logger.warn("Game state build failed: room not found for gameId {}", gameId);
            throw new ResourceNotFoundException("Room not found");
        }

        boolean autoAdvancing = game.getScheduledTaskDeadline(ScheduledGameTask.AUTO_ADVANCE) != null;
        HandSettlementResult rawSettlement = game.getLastSettlementResult();
        boolean isShowdown = game.getCurrentPhase() == GamePhase.SHOWDOWN || game.isReadyCountdownActive();
        HandSettlementResult projectionSettlement = rawSettlement != null && rawSettlement.settled()
                ? rawSettlement
                : new HandSettlementResult(Map.of(), Map.of(), Set.of(), 0, false);
        boolean handSettled = projectionSettlement.settled();
        boolean revealSettledHands = handSettled && game.getPlayers().stream()
                .filter(player -> projectionSettlement.winnerIds().contains(player.getPlayerId()))
                .anyMatch(player -> player.getHandRank() != null && player.getHandRank() != HandRank.NO_HAND);

        Player currentPlayer = !isShowdown && !autoAdvancing && !handSettled && !game.getActivePlayers().isEmpty()
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

        List<PublicPlayerState> playerStateList = new ArrayList<>();
        for (Player player : game.getPlayers()) {
            boolean isActive = !player.getHasFolded() && !player.getIsOut();
            Boolean isWinner = handSettled ? projectionSettlement.winnerIds().contains(player.getPlayerId()) : null;
            Integer chipsWon = handSettled
                    ? projectionSettlement.contestedAwards().getOrDefault(player.getPlayerId(), 0)
                    : null;
            playerStateList.add(toPublicPlayerState(
                    player,
                    player.equals(currentPlayer),
                    smallBlindPlayerId,
                    bigBlindPlayerId,
                    handSettled && !projectionSettlement.winnerIds().isEmpty() && isActive ? player.getHandRank() : null,
                    handSettled && !projectionSettlement.winnerIds().isEmpty() && isActive ? player.getBestHand() : null,
                    isWinner,
                    chipsWon,
                    revealSettledHands && isActive ? player.getHoleCards() : null));
        }

        String claimWinPlayerName = computeClaimWinPlayerName(game);
        boolean claimWinAvailable = claimWinPlayerName != null;
        List<String> winnerNames = handSettled
                ? game.getPlayers().stream()
                        .filter(player -> projectionSettlement.winnerIds().contains(player.getPlayerId()))
                        .map(Player::getName)
                        .toList()
                : null;
        Integer winningsPerPlayer = handSettled
                ? computeEqualWinnerAward(projectionSettlement.winnerIds(), projectionSettlement.contestedAwards())
                : null;

        return new PublicGameStateResponse(
                room.getMaxPlayers(),
                game.getPot(),
                game.getPotBreakdown(),
                game.getUncalledAmount(),
                game.getCurrentPhase(),
                game.getCurrentHighestBet(),
                game.getCommunityCards(),
                playerStateList,
                currentPlayer != null ? currentPlayer.getName() : null,
                currentPlayer != null ? currentPlayer.getPlayerId() : null,
                winnerNames,
                winningsPerPlayer,
                autoAdvancing,
                autoAdvancing ? "Auto-advancing..." : null,
                game.isReadyCountdownActive(),
                game.getReadyCountdownDeadlineEpochMs(),
                claimWinAvailable,
                claimWinPlayerName,
                handSettled,
                handSettled ? projectionSettlement.remainder() : null,
                currentPlayer == null ? List.of() : game.getLegalActionsFor(currentPlayer),
                currentPlayer == null ? null : game.getBigBlind(),
                currentPlayer == null ? null : game.getMinimumRaiseAmount(currentPlayer),
                game.getHandNumber(),
                game.getReadyGateState(),
                clock.millis(),
                game.getStateRevision(),
                currentPlayer == null ? null : game.getScheduledTaskDeadline(ScheduledGameTask.TURN_TIMEOUT),
                room.getInactivityDeadlineEpochMs());
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
                player.getIsAllIn(),
                isCurrentPlayer,
                player.getHasFolded(),
                player.getPlayerId().equals(smallBlindPlayerId),
                player.getPlayerId().equals(bigBlindPlayerId),
                handRank,
                bestHand,
                isWinner,
                chipsWon,
                holeCards,
                player.getDisconnectDeadlineEpochMs(),
                player.getIsReadyForNextHand());
    }

    private String resolvePlayerStatus(Player player) {
        if (player.getIsOut()) {
            return PlayerStatus.OUT.getStatus();
        }
        if (player.getHasFolded()) {
            return PlayerStatus.FOLDED.getStatus();
        }
        if (player.getIsDisconnected()) {
            return PlayerStatus.DISCONNECTED.getStatus();
        }
        if (player.getIsAllIn()) {
            return PlayerStatus.ALL_IN.getStatus();
        }
        return PlayerStatus.ACTIVE.getStatus();
    }

    private String computeClaimWinPlayerName(Game game) {
        if (!TournamentEligibility.canClaim(game)) {
            return null;
        }
        return game.getPlayers().stream()
                .filter(player -> TournamentEligibility.isContender(game, player))
                .filter(player -> !player.getIsDisconnected())
                .map(Player::getName)
                .findFirst()
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

    private void sendToPlayerAfterSave(String gameId, String playerName, String destination, Object payload) {
        Room room = roomService.getRoom(gameId);
        if (room == null) {
            return;
        }
        // Capture membership ID before queuing so a replacement player with the same name never receives it.
        String membershipId = room.getMembershipId(playerName);
        if (membershipId == null || membershipId.isBlank()) {
            return;
        }
        sendToPrincipalAfterSave(new PlayerPrincipal(playerName, gameId, membershipId), destination, payload);
    }

    private void sendToPrincipalAfterSave(PlayerPrincipal origin, String destination, Object payload) {
        if (origin.membershipId() == null || origin.membershipId().isBlank()) {
            return;
        }
        String user = origin.getName();
        PostSaveActions.runAfterSave(() -> messagingTemplate.convertAndSendToUser(user, destination, payload));
    }
}
