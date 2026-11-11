package com.pokergame.service;

import com.pokergame.dto.response.GameEndResponse;
import com.pokergame.dto.response.ActionErrorResponse;
import com.pokergame.dto.response.PrivatePlayerState;
import com.pokergame.dto.response.PublicPlayerState;
import com.pokergame.dto.response.PlayerNotificationResponse;
import com.pokergame.dto.response.PublicGameStateResponse;
import com.pokergame.enums.GamePhase;
import com.pokergame.enums.HandRank;
import com.pokergame.enums.PlayerStatus;
import com.pokergame.enums.ReadyGateState;
import com.pokergame.enums.ResponseMessage;
import com.pokergame.enums.PlayerAction;
import com.pokergame.enums.RejectedAction;
import com.pokergame.exception.BadRequestException;
import com.pokergame.exception.ResourceNotFoundException;
import com.pokergame.model.Card;
import com.pokergame.model.Game;
import com.pokergame.model.Player;
import com.pokergame.model.Room;
import com.pokergame.model.TournamentEligibility;
import com.pokergame.persistence.transaction.DurableTransactionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import com.pokergame.model.HandSettlementResult;
import com.pokergame.security.PlayerPrincipal;
import com.pokergame.enums.ScheduledGameTask;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import static java.util.Objects.requireNonNull;

/**
 * Service class responsible for managing and broadcasting game state.
 * Handles all game state updates, notifications, and WebSocket broadcasts.
 */
@Service
public class GameStateService {

    private static final Logger logger = LoggerFactory.getLogger(GameStateService.class);

    private final SimpMessagingTemplate messagingTemplate;

    private final RoomService roomService;

    private GameLifecycleService gameLifecycleService;

    private RoomCoordinator roomCoordinator;

    private final Map<String, HandSettlementResult> retainedSettlements = new ConcurrentHashMap<>();

    /**
     * Creates the projection service with room authorization context and the STOMP
     * publisher used after durable commits.
     *
     * @param roomService       room membership and host context
     * @param messagingTemplate STOMP publisher
     */
    public GameStateService(RoomService roomService, SimpMessagingTemplate messagingTemplate) {
        this.roomService = roomService;
        this.messagingTemplate = messagingTemplate;
    }

    @Autowired(required = false)
    public void setGameLifecycleService(@Lazy GameLifecycleService gameLifecycleService) {
        this.gameLifecycleService = gameLifecycleService;
    }

    @Autowired(required = false)
    public void setRoomCoordinator(RoomCoordinator roomCoordinator) {
        this.roomCoordinator = roomCoordinator;
    }

    /**
     * Builds the current public game-state snapshot for REST consumers using its immutable identifier.
     *
     * @param gameId game identifier
     * @return public game-state DTO for the requested game
     */
    public PublicGameStateResponse getPublicGameStateSnapshot(String gameId) {
        if (gameLifecycleService == null) {
            throw new IllegalStateException("GameLifecycleService not configured");
        }
        Game game = gameLifecycleService.getGame(gameId);
        if (game == null || game.isGameOver()) {
            throw new ResourceNotFoundException("Game not found");
        }
        return getPublicGameStateSnapshot(gameId, game);
    }

    /**
     * Builds the private game-state snapshot for a specific player using immutable identifiers.
     *
     * @param gameId     game identifier
     * @param playerName authenticated player name
     * @return private state containing the player's hole cards
     */
    public PrivatePlayerState getPrivatePlayerStateSnapshot(String gameId, String playerName) {
        if (gameLifecycleService == null) {
            throw new IllegalStateException("GameLifecycleService not configured");
        }
        Game game = gameLifecycleService.getGame(gameId);
        if (game == null || game.isGameOver()) {
            throw new ResourceNotFoundException("Game not found");
        }
        return getPrivatePlayerStateSnapshot(game, playerName);
    }

    /**
     * Evicts any process-local retained settlement for the specified game when the
     * game is cleaned up, evicted, or replaced.
     *
     * @param gameId game identifier
     */
    public void clearRetainedSettlement(String gameId) {
        if (gameId != null) {
            retainedSettlements.remove(gameId);
        }
    }

    boolean hasRetainedSettlement(String gameId) {
        return gameId != null && retainedSettlements.containsKey(gameId);
    }

    /**
     * Builds the current public game-state snapshot for REST consumers.
     *
     * @param gameId game identifier
     * @param game   active game instance
     * @return public game-state DTO for the requested game
     */
    public PublicGameStateResponse getPublicGameStateSnapshot(String gameId, Game game) {
        Room room = roomService.getRoom(gameId);
        if (room == null) {
            logger.warn("Game state build failed: room not found for gameId {}", gameId);
            throw new ResourceNotFoundException("Room not found");
        }
        synchronized (game) {
            return buildPublicGameStateResponse(gameId, game, room);
        }
    }

    /**
     * Builds the private game-state snapshot for a specific player.
     *
     * @param game       active game instance
     * @param playerName authenticated player name
     * @return private state containing the player's hole cards
     */
    public PrivatePlayerState getPrivatePlayerStateSnapshot(Game game, String playerName) {
        synchronized (game) {
            Player player = game.getPlayers().stream()
                    .filter(p -> p.getName().equals(playerName))
                    .findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException("Player not found in game"));

            return buildPrivatePlayerState(game, player);
        }
    }

    /**
     * Broadcasts the current game state to all players in the game.
     * Each player receives a personalised view showing only their own hole cards.
     *
     * @param gameId the unique identifier of the game
     * @param game   the Game object containing the current state
     * @throws BadRequestException if the game is null
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

        PublicGameStateResponse publicResponse;
        Map<String, PrivatePlayerState> playerPrivateStates = new HashMap<>();

        synchronized (game) {
            game.incrementStateRevision();
            publicResponse = buildPublicGameStateResponse(gameId, game, room);
            for (Player player : game.getPlayers()) {
                playerPrivateStates.put(player.getName(), buildPrivatePlayerState(game, player));
            }
        }

        sendAfterCommit("/game/" + gameId, publicResponse);

        // Sending a personalised game state to each player using secure user destinations
        playerPrivateStates.forEach((playerName, privateState) -> {
            sendToPlayerAfterCommit(
                    gameId, playerName,
                    "/queue/private",
                    privateState);
        });
    }

    /**
     * Broadcasts showdown results with winner information to all players.
     * Reveals hole cards, hand ranks, and best hands for winning players only.
     *
     * @param gameId            the unique identifier of the game
     * @param game              the Game object containing the current state
     * @param winners           the list of Player objects who won the hand
     * @param winningsPerPlayer the number of chips each winner receives
     */
    public void broadcastShowdownResults(String gameId, Game game, List<Player> winners, int winningsPerPlayer) {
        if (game == null) {
            logger.warn("Cannot broadcast showdown - game {} not found", gameId);
            return;
        }

        Room room = roomService.getRoom(gameId);
        int maxPlayers = room != null ? room.getMaxPlayers() : 0;

        PublicGameStateResponse showdownResponse;
        List<String> winnerNames;

        synchronized (game) {
            game.incrementStateRevision();

            // Showdown means no one is waiting to act - reporting a stale "current
            // player" pointer here previously made the client briefly think it was
            // someone's turn (showing Fold/Check) between the showdown broadcast and
            // the later GAME_END/next-hand broadcast, so these are intentionally null.
            String currentPlayerName = null;
            String currentPlayerId = null;

            HandSettlementResult settlement = game.getLastSettlementResult();
            boolean retainedSettlement = settlement != null && settlement.settled();
            Set<String> winnerIds;
            Map<String, Integer> contestedAwards;
            HandSettlementResult effectiveSettlement;
            if (settlement != null && settlement.settled()) {
                winnerIds = settlement.winnerIds();
                contestedAwards = settlement.contestedAwards();
                effectiveSettlement = settlement;
            } else {
                winnerIds = winners.stream().map(Player::getPlayerId).collect(Collectors.toSet());
                contestedAwards = winners.stream().collect(Collectors.toMap(Player::getPlayerId,
                        ignored -> winningsPerPlayer, (left, right) -> left));
                effectiveSettlement = new HandSettlementResult(contestedAwards, Map.of(), winnerIds, 0, true);
            }
            if (!game.isGameOver()) {
                retainedSettlements.put(gameId, effectiveSettlement);
            } else {
                retainedSettlements.remove(gameId);
            }
            List<Player> projectedWinners = game.getPlayers().stream()
                    .filter(player -> winnerIds.contains(player.getPlayerId())).toList();
            winnerNames = projectedWinners.stream().map(Player::getName).toList();
            Integer legacyWinnings = computeEqualWinnerAward(winnerIds, contestedAwards);

            // Check if this is an actual showdown (i.e. not a win by fold)
            boolean isActualShowdown = projectedWinners.stream()
                    .anyMatch(w -> w.getHandRank() != null && w.getHandRank() != HandRank.NO_HAND);
            String smallBlindPlayerId = game.getSmallBlindPlayerId();
            String bigBlindPlayerId = game.getBigBlindPlayerId();

            // Convert players to PublicPlayerState DTOs with showdown information
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

            // Create PublicGameStateResponse DTO with showdown information
            showdownResponse = new PublicGameStateResponse(
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
                    retainedSettlement,
                    retainedSettlement ? effectiveSettlement.remainder() : null,
                    null, null, null, game.getHandNumber(), game.getReadyGateState(), System.currentTimeMillis(),
                    game.getStateRevision());
        }

        // Broadcast showdown results to all players
        sendAfterCommit("/game/" + gameId, showdownResponse);

        logger.info("Broadcasted showdown results for game {} with {} winner(s): {}",
                gameId, winnerNames.size(), winnerNames);
        logger.debug("Showdown game state - winners: {}, winnerCount: {}, winnings per player: {}",
                winnerNames, winnerNames.size(), winningsPerPlayer);
    }

    /**
     * Broadcasts game state with auto-advance information when all players are
     * all-in.
     * Includes special flags and messages to notify clients of automatic
     * progression.
     *
     * @param gameId  the unique identifier of the game
     * @param game    the Game object containing the current state
     * @param message the message to display to players about auto-advance
     *                status
     */
    public void broadcastGameStateWithAutoAdvance(String gameId, Game game, String message) {
        if (game == null) {
            logger.warn("Cannot broadcast auto-advance state - game {} not found", gameId);
            return;
        }

        Room room = roomService.getRoom(gameId);
        int maxPlayers = room != null ? room.getMaxPlayers() : 0;

        PublicGameStateResponse autoAdvanceResponse;

        synchronized (game) {
            game.incrementStateRevision();

            // Get current player information
            String currentPlayerName = null;
            String currentPlayerId = null;
            String smallBlindPlayerId = game.getSmallBlindPlayerId();
            String bigBlindPlayerId = game.getBigBlindPlayerId();

            // Convert players to PlayerState DTOs
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

            // Create PublicGameStateResponse DTO with auto-advance fields
            autoAdvanceResponse = new PublicGameStateResponse(
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
                    null, // winners
                    null, // winningsPerPlayer
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
                    game.getHandNumber(), game.getReadyGateState(), System.currentTimeMillis(),
                    game.getStateRevision());
        }

        logger.info("Broadcasting auto-advance state for game {}: {}", gameId, message);
        sendAfterCommit("/game/" + gameId, autoAdvanceResponse);
    }

    /**
     * Broadcasts a notification that auto-advance to showdown is starting.
     * Sent when no further betting actions are possible.
     *
     * @param gameId the unique identifier of the game
     * @param game   the Game object containing the current state
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
     * Broadcasts a notification that auto-advance has completed.
     * Sent after all community cards have been dealt and showdown is ready.
     *
     * @param gameId the unique identifier of the game
     * @param game   the Game object containing the current state
     */
    public void broadcastAutoAdvanceComplete(String gameId, Game game) {
        if (game == null) {
            logger.warn("Cannot broadcast auto-advance complete - game {} not found", gameId);
            return;
        }

        logger.info("Broadcasting auto-advance complete for game {}: {}", gameId, game.getCommunityCards());
        sendAfterCommit("/game/" + gameId,
                new PlayerNotificationResponse(ResponseMessage.AUTO_ADVANCE_COMPLETE, "", null, gameId));
    }

    /**
     * Sends a notification message to a specific player.
     * Used for action conversions and other player-specific alerts.
     *
     * @param gameId     the unique identifier of the game
     * @param playerName the name of the player to notify
     * @param message    the notification message content
     */
    public void sendPlayerNotification(String gameId, String playerName, String message) {
        PlayerNotificationResponse notification = new PlayerNotificationResponse(
                ResponseMessage.PLAYER_NOTIFICATION,
                message,
                playerName,
                gameId);

        sendAfterCommit("/game/" + gameId, notification);
    }

    /**
     * Sends a private notification message to a specific player's private channel.
     * Used for action errors or private-only feedback.
     *
     * @param gameId     the unique identifier of the game
     * @param playerName the name of the player to notify
     * @param message    the notification message content
     * @param type       the type of notification (e.g. "ACTION_ERROR")
     */
    public void sendPrivatePlayerNotification(String gameId, String playerName, String message, ResponseMessage type) {
        PlayerNotificationResponse notification = new PlayerNotificationResponse(
                type,
                message,
                playerName,
                gameId);

        sendToPlayerAfterCommit(
                gameId, playerName,
                "/queue/private",
                notification);
    }

    /** Replies to the originating membership, even if that player name has since been reused. */
    public void sendPrivatePlayerNotification(PlayerPrincipal origin, String message, ResponseMessage type) {
        sendToPrincipalAfterCommit(origin, "/queue/private", new PlayerNotificationResponse(
                type, message, origin.playerName(), origin.roomId()));
    }

    /** Replies to a failed gameplay frame using its originating membership identity. */
    public void sendActionError(PlayerPrincipal origin, String code, PlayerAction action, String message) {
        sendActionError(origin, code, RejectedAction.from(action), message);
    }

    /** Replies to failed gameplay or readiness frames without resolving a replacement membership. */
    public void sendActionError(PlayerPrincipal origin, String code, RejectedAction action, String message) {
        sendToPrincipalAfterCommit(origin, "/queue/private", new ActionErrorResponse(code, action, message));
    }

    /** Publishes a rejected action to its player's private WebSocket queue. */
    public void sendActionError(String gameId, String playerName, String code, PlayerAction action, String message) {
        sendActionError(gameId, playerName, code, RejectedAction.from(action), message);
    }

    /** Publishes gameplay or readiness rejection to the current membership private queue. */
    public void sendActionError(String gameId, String playerName, String code, RejectedAction action, String message) {
        sendToPlayerAfterCommit(gameId, playerName, "/queue/private",
                new ActionErrorResponse(code, action, message));
    }

    /**
     * Broadcasts a game end message when a winner is determined.
     * Sent when only one player remains with chips. Carries a complete, revealed
     * final-state snapshot so the client can freeze it for the post-game review
     * screen, independent of subsequent server cleanup.
     *
     * @param gameId    the unique identifier of the game
     * @param game      the Game object containing the final state
     * @param winner    the Player object representing the game winner
     * @param isForfeit true if the game ended due to a player leaving/disconnecting
     */
    public void broadcastGameEnd(String gameId, Game game, Player winner, boolean isForfeit) {
        if (winner == null) {
            logger.warn("Cannot broadcast game end for {} - winner is null", gameId);
            return;
        }

        Room room = roomService.getRoom(gameId);
        int maxPlayers = room == null ? 0 : room.getMaxPlayers();
        GameEndResponse event;
        synchronized (game) {
            clearRetainedSettlement(gameId);
            if (game.getRetainedTerminalResult() != null) {
                event = game.getRetainedTerminalResult();
            } else {
                event = captureGameEnd(gameId, game, winner, isForfeit, maxPlayers);
                game.setRetainedTerminalResult(event);
            }
        }
        sendGameEnd(event);

        logger.info("Game {} completed - Winner: {} with {} chips",
                gameId, winner.getName(), winner.getChips());
    }

    /** Captures the immutable terminal event while the caller holds the game lock. */
    public GameEndResponse captureGameEnd(String gameId, Game game, Player winner, boolean isForfeit, int maxPlayers) {
        if (winner == null) return null;
        clearRetainedSettlement(gameId);
        if (game != null) {
            game.incrementStateRevision();
        }
        return projectGameEnd(gameId, game, winner, isForfeit, maxPlayers);
    }

    /** Captures the immutable terminal abandonment event while the caller holds the game lock. */
    public GameEndResponse captureAbandonment(String gameId, Game game, int maxPlayers) {
        clearRetainedSettlement(gameId);
        if (game != null) {
            game.incrementStateRevision();
        }
        return projectAbandonment(gameId, game, maxPlayers);
    }

    /**
     * Projects a terminal event snapshot without mutating the game's stateRevision
     * or process-local state, suitable for read-only review queries.
     */
    public GameEndResponse projectGameEnd(String gameId, Game game, Player winner, boolean isForfeit, int maxPlayers) {
        if (winner == null) return null;
        String message = "🏆 " + winner.getName() + " wins the game with " + winner.getChips() + " chips!";
        PublicGameStateResponse finalState = game == null ? null
                : buildFinalStateResponse(gameId, game, winner, isForfeit, maxPlayers);
        return new GameEndResponse(ResponseMessage.GAME_END.getMessage(), gameId, winner.getName(),
                winner.getChips(), isForfeit, message, finalState);
    }

    /**
     * Projects a terminal abandonment snapshot without mutating stateRevision,
     * suitable for read-only review queries.
     */
    public GameEndResponse projectAbandonment(String gameId, Game game, int maxPlayers) {
        String message = "Game abandoned due to inactivity.";
        PublicGameStateResponse finalState = game == null ? null
                : buildAbandonmentFinalStateResponse(gameId, game, maxPlayers);
        return new GameEndResponse(ResponseMessage.GAME_END.getMessage(), gameId, null,
                null, false, true, "INACTIVITY", message, finalState);
    }

    /** Publishes the frozen terminal result after the accepted mutation completes. */
    public void sendGameEnd(GameEndResponse response) {
        if (response != null) sendAfterCommit("/game/" + response.gameId(), response);
    }

    private int resolveMaxPlayers(String gameId, Game game, int maxPlayers) {
        if (maxPlayers >= 2 && maxPlayers <= 10) {
            return maxPlayers;
        }
        Room room = roomService != null ? roomService.getRoom(gameId) : null;
        if (room != null && room.getMaxPlayers() >= 2 && room.getMaxPlayers() <= 10) {
            return room.getMaxPlayers();
        }
        int retainedPlayerCount = game != null && game.getPlayers() != null ? game.getPlayers().size() : 2;
        return Math.max(2, Math.min(10, retainedPlayerCount));
    }

    private PublicGameStateResponse buildAbandonmentFinalStateResponse(String gameId, Game game, int maxPlayers) {
        game = requireNonNull(game, "game");
        int resolvedMaxPlayers = resolveMaxPlayers(gameId, game, maxPlayers);

        String smallBlindPlayerId = game.getSmallBlindPlayerId();
        String bigBlindPlayerId = game.getBigBlindPlayerId();

        List<PublicPlayerState> playersList = game.getPlayers().stream().map(player ->
            toPublicPlayerState(
                    player,
                    false,
                    smallBlindPlayerId,
                    bigBlindPlayerId,
                    null,
                    List.of(),
                    false,
                    null,
                    null)
        ).toList();

        List<String> winnerNames = List.of();
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
                System.currentTimeMillis(),
                game.getStateRevision(),
                null,
                null);
    }

    /**
     * Builds a revealed final-state projection for the game-end review screen. Unlike
     * the live-game projection, this reveals hole cards, hand ranks, and best hands
     * for all non-folded, non-out players, and
     * marks the winner explicitly.
     *
     * @param gameId          the unique identifier of the game
     * @param game            the Game object containing the final state
     * @param winner          the winning player, or null if undetermined
     * @param isForfeit       true if the game ended due to forfeit
     * @param maxPlayers      configured max players from room
     * @return a {@link PublicGameStateResponse} representing the frozen final state
     */
    private PublicGameStateResponse buildFinalStateResponse(String gameId, Game game, Player winner,
            boolean isForfeit, int maxPlayers) {
        game = requireNonNull(game, "game");
        int resolvedMaxPlayers = resolveMaxPlayers(gameId, game, maxPlayers);

        boolean contestedShowdown = game.getCurrentPhase() == GamePhase.SHOWDOWN
                && game.getLastSettlementResult() != null && game.getLastSettlementResult().settled()
                && game.getActivePlayers().stream().filter(player -> !player.getHasFolded() && !player.getIsOut()).count() > 1;
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
                System.currentTimeMillis(),
                game.getStateRevision());
    }

    /**
     * Builds a PublicGameStateResponse object to be shown to all players in a game.
     *
     * @param gameId the unique identifier of the game
     * @param game   the Game object containing the current state
     * @param room   the Room object associated with this game
     * @return a {@link PublicGameStateResponse}
     * 
     * @throws ResourceNotFoundException if the room is not found
     */
    private PublicGameStateResponse buildPublicGameStateResponse(String gameId, Game game, Room room) {
        if (room == null) {
            logger.warn("Game state build failed: room not found for gameId {}", gameId);
            throw new ResourceNotFoundException("Room not found");
        }
        List<PublicPlayerState> playerStateList = new ArrayList<>();
        boolean autoAdvancing = game.getScheduledTaskDeadline(ScheduledGameTask.AUTO_ADVANCE) != null;
        HandSettlementResult rawSettlement = game.getLastSettlementResult();
        boolean isShowdown = game.getCurrentPhase() == GamePhase.SHOWDOWN || game.isReadyCountdownActive();
        if (!isShowdown || game.isGameOver()) {
            retainedSettlements.remove(gameId);
        }
        final HandSettlementResult settlement = (rawSettlement != null && rawSettlement.settled())
                ? rawSettlement
                : (!game.isGameOver() && isShowdown ? retainedSettlements.get(gameId) : null);
        HandSettlementResult projectionSettlement = settlement != null && settlement.settled()
                ? settlement
                : new HandSettlementResult(Map.of(), Map.of(), Set.of(), 0, false);
        boolean handSettled = projectionSettlement.settled();
        boolean revealSettledHands = handSettled && game.getPlayers().stream()
                .filter(player -> projectionSettlement.winnerIds().contains(player.getPlayerId()))
                .anyMatch(player -> player.getHandRank() != null
                        && player.getHandRank() != HandRank.NO_HAND);
        Player currentPlayer = !isShowdown && !autoAdvancing && !handSettled && !game.getActivePlayers().isEmpty()
                ? game.getCurrentPlayer() : null;
        String smallBlindPlayerId = game.getSmallBlindPlayerId();
        String bigBlindPlayerId = game.getBigBlindPlayerId();
        if (currentPlayer != null) {
            logger.debug("Building game state - current player: {} (ID: {})", currentPlayer.getName(),
                    currentPlayer.getPlayerId());
        } else {
            logger.debug("Building game state - no active players found");
        }
        for (Player player : game.getPlayers()) {
            boolean isActive = !player.getHasFolded() && !player.getIsOut();
            Boolean isWinner = handSettled ? projectionSettlement.winnerIds().contains(player.getPlayerId()) : null;
            Integer chipsWon = handSettled ? projectionSettlement.contestedAwards().getOrDefault(player.getPlayerId(), 0) : null;
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
                ? game.getPlayers().stream().filter(player -> projectionSettlement.winnerIds().contains(player.getPlayerId()))
                        .map(Player::getName).toList()
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
                game.getHandNumber(), game.getReadyGateState(), System.currentTimeMillis(),
                game.getStateRevision(), currentPlayer == null ? null : game.getScheduledTaskDeadline(ScheduledGameTask.TURN_TIMEOUT),
                room != null ? room.getInactivityDeadlineEpochMs() : null);

    }

    /** Returns the shared contested award when every winner received the same amount. */
    private Integer computeEqualWinnerAward(Set<String> winnerIds, Map<String, Integer> contestedAwards) {
        if (winnerIds == null || winnerIds.isEmpty()) {
            return null;
        }
        List<Integer> awards = winnerIds.stream()
                .map(id -> contestedAwards.getOrDefault(id, 0))
                .toList();
        return awards.stream().distinct().count() == 1 ? awards.getFirst() : null;
    }

    /** Maps a player and explicitly selected reveal/award metadata into the public projection. */
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

    /**
     * Applies a single precedence order to overlapping player flags so clients receive
     * one unambiguous render state.
     *
     * @param player player whose status is projected
     * @return client-visible status value
     */
    String resolvePlayerStatus(Player player) {
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

    /**
     * Projects claim availability only when exactly one eligible player is connected
     * and at least one opponent is disconnected.
     *
     * @param game game whose claim affordance is projected
     * @return eligible claimant name, or {@code null} when no claim is legal
     */
    private String computeClaimWinPlayerName(Game game) {
        if (!TournamentEligibility.canClaim(game)) return null;
        return game.getPlayers().stream().filter(player -> TournamentEligibility.isContender(game, player))
                .filter(player -> !player.getIsDisconnected()).map(Player::getName).findFirst().orElse(null);
    }

    /**
     * Builds a PrivatePlayerState object to show to specific players.
     *
     * @param game   active game instance
     * @param player player object
     * @return a {@link PrivatePlayerState}
     */
    private PrivatePlayerState buildPrivatePlayerState(Game game, Player player) {
        List<Card> holeCards = player.getHoleCards() == null
                ? List.of()
                : new ArrayList<>(player.getHoleCards());
        return new PrivatePlayerState(
                player.getPlayerId(),
                game.getHandNumber(),
                holeCards);
    }

    /**
     * Releases shared state after the mutation is accepted and its save attempt
     * finishes, including accepted gameplay that continues in memory after a save failure.
     *
     * @param destination shared STOMP destination
     * @param payload     accepted public payload
     */
    private void sendAfterCommit(String destination, Object payload) {
        DurableTransactionContext.afterCommit(() -> messagingTemplate.convertAndSend(destination, payload));
    }

    /**
     * Captures the active membership before deferring delivery, so a queued message
     * can never be redirected to a replacement player with the same display name.
     * Delivery follows acceptance even when saving fails and gameplay continues in memory.
     *
     * @param gameId room/game containing the current membership
     * @param playerName player receiving this accepted private projection
     * @param destination private user destination
     * @param payload accepted private payload
     */
    private void sendToPlayerAfterCommit(String gameId, String playerName, String destination, Object payload) {
        Room room = roomService.getRoom(gameId);
        if (room == null) return;
        // This atomic lookup must not acquire Room: some publishers hold Game.
        String membershipId = room.getMembershipId(playerName);
        if (membershipId == null || membershipId.isBlank()) return;
        sendToPrincipalAfterCommit(new PlayerPrincipal(playerName, gameId, membershipId), destination, payload);
    }

    /** Captures a frame's original private destination without consulting mutable room membership. */
    private void sendToPrincipalAfterCommit(PlayerPrincipal origin, String destination, Object payload) {
        if (origin.membershipId() == null || origin.membershipId().isBlank()) return;
        String user = origin.getName();
        DurableTransactionContext.afterCommit(
                () -> messagingTemplate.convertAndSendToUser(user, destination, payload));
    }

}
