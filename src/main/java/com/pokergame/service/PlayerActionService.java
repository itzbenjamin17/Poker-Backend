package com.pokergame.service;

import static java.util.Objects.requireNonNull;

import com.pokergame.dto.internal.PlayerDecision;
import com.pokergame.dto.internal.TurnOutcome;
import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.enums.GamePhase;
import com.pokergame.enums.PlayerAction;
import com.pokergame.enums.ResponseMessage;
import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.exception.BadRequestException;
import com.pokergame.exception.ResourceNotFoundException;
import com.pokergame.exception.UnauthorisedActionException;
import com.pokergame.model.Game;
import com.pokergame.model.Player;
import com.pokergame.persistence.transaction.DurableMutation;
import com.pokergame.util.LockLease;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Validates and applies in-game poker actions (fold, check, call, bet, raise, all-in) for the
 * active player and broadcasts the resulting game state.
 */
@Service
public class PlayerActionService {
    private static final Logger logger = LoggerFactory.getLogger(PlayerActionService.class);
    private final GameLifecycleService gameLifecycleService;
    private final GameStateService gameStateService;
    private final RoomCoordinator roomCoordinator;
    private final Clock clock;

    public PlayerActionService(
            GameLifecycleService gameLifecycleService,
            GameStateService gameStateService,
            RoomCoordinator roomCoordinator,
            Clock clock) {
        this.gameLifecycleService = gameLifecycleService;
        this.gameStateService = gameStateService;
        this.roomCoordinator = roomCoordinator;
        this.clock = clock;
    }

    /**
     * Returns whether the inactivity deadline for {@code gameId} has already passed.
     *
     * @param gameId the unique ID of the game
     * @return {@code true} if the game's inactivity deadline has elapsed
     */
    public boolean isGameInactivityExpired(String gameId) {
        return gameLifecycleService.isGameInactivityExpired(gameId);
    }

    /**
     * Validates a player's requested poker action, applies it to the game, resets the inactivity
     * timer, and publishes updated turn or showdown state.
     *
     * @param gameId        the unique ID of the game
     * @param actionRequest the requested action type and optional wager amount
     * @param playerName    the authenticated player's name
     * @throws ResourceNotFoundException   if the game does not exist
     * @throws BadRequestException         if the game expired/ended, the turn deadline passed,
     *                                     or the action request is invalid
     * @throws UnauthorisedActionException if it is not {@code playerName}'s turn or the player is
     *                                     marked disconnected
     */
    @DurableMutation(roomId = "#gameId")
    public void processPlayerAction(String gameId, PlayerActionRequest actionRequest, String playerName) {
        requireNonNull(gameId, "gameId must not be null");
        requireNonNull(playerName, "playerName must not be null");
        try (LockLease _ = roomCoordinator.acquire(gameId)) {
            if (isGameInactivityExpired(gameId)) {
                logger.warn("Player {} attempted to act in game {} at or after inactivity deadline (now={})",
                        playerName, gameId, clock.millis());
                gameLifecycleService.abandonGameDueToInactivity(gameId);
                throw new BadRequestException(
                        "Game has expired due to inactivity.",
                        "GAME_EXPIRED",
                        "Game has expired due to inactivity");
            }

            Game game = gameLifecycleService.getGame(gameId);
            if (game == null) {
                logger.warn("Game not found for ID: {} when trying to process player action", gameId);
                throw new ResourceNotFoundException("Game not found:");
            }
            if (game.isGameOver()) {
                logger.warn("Player {} attempted to act in game {} after it has finished", playerName, gameId);
                throw new BadRequestException("This game has finished.", "GAME_FINISHED", "Game has finished");
            }
            if (game.getCurrentPhase() == GamePhase.SHOWDOWN) {
                logger.warn("Player {} attempted to act in game {} after the hand was resolved (phase=SHOWDOWN)",
                        playerName, gameId);
                throw new BadRequestException("This hand is already complete.", "HAND_COMPLETE", "Hand is complete");
            }
            if (game.getScheduledTaskDeadline(ScheduledGameTask.AUTO_ADVANCE) != null) {
                logger.warn("Player {} attempted to act in game {} while it is auto-advancing", playerName, gameId);
                throw new BadRequestException(
                        "Actions are not allowed while the game is auto-advancing.",
                        "HAND_ADVANCING",
                        "Wait for the next turn");
            }

            Long turnDeadline = game.getScheduledTaskDeadline(ScheduledGameTask.TURN_TIMEOUT);
            if (turnDeadline != null && clock.millis() >= turnDeadline) {
                logger.warn("Player {} attempted to act in game {} at or after turn deadline (now={}, deadline={})",
                        playerName, gameId, clock.millis(), turnDeadline);
                throw new BadRequestException("Turn deadline expired.", "TURN_EXPIRED", "Your turn has expired");
            }

            Player currentPlayer = getCurrentPlayer(actionRequest, game);

            logger.debug("Processing player action - Game: {}, Player: {}, Action: {}",
                    gameId, currentPlayer.getName(), actionRequest.action());
            logger.debug("Game state - Phase: {}, Current bet: {}",
                    game.getCurrentPhase(), game.getCurrentHighestBet());

            if (!currentPlayer.getName().equals(playerName)) {
                logger.warn("Player name mismatch: expected {}, got {}", currentPlayer.getName(), playerName);
                throw new UnauthorisedActionException(
                        "It's not your turn. Current player is: " + currentPlayer.getName(),
                        "NOT_YOUR_TURN",
                        "Wait for your turn");
            }
            if (currentPlayer.getIsDisconnected()) {
                logger.warn("Disconnected player {} attempted to act in game {}", playerName, gameId);
                throw new UnauthorisedActionException(
                        "You are disconnected. Reconnect to continue your turn.",
                        "DISCONNECTED",
                        "Reconnect to act");
            }

            PlayerDecision decision = new PlayerDecision(
                    actionRequest.action(),
                    actionRequest.amount() != null ? actionRequest.amount() : 0,
                    currentPlayer.getPlayerId());

            logger.debug("Processing decision: {}", decision);
            game.clearScheduledTask(ScheduledGameTask.TURN_TIMEOUT);
            gameLifecycleService.cancelTurnTimeout(gameId);

            TurnOutcome outcome = game.processPlayerDecision(currentPlayer, decision);
            logger.debug("Decision processed successfully");
            gameLifecycleService.resetGameInactivity(gameId);

            if (outcome.conversionMessage() != null) {
                logger.info("Sending conversion message to player {}: {}",
                        currentPlayer.getName(), outcome.conversionMessage());
                gameStateService.sendPrivatePlayerNotification(
                        gameId, currentPlayer.getName(), outcome.conversionMessage(), ResponseMessage.PLAYER_NOTIFICATION);
            }

            try {
                gameLifecycleService.applyTurnOutcome(gameId, game, outcome, true);
            } catch (Exception e) {
                logger.error("Error in post-processing for game {} (action was successful): {}",
                        gameId, e.getMessage(), e);
                try {
                    gameStateService.broadcastGameState(game);
                } catch (Exception broadcastError) {
                    logger.error("Failed to re-broadcast game state for game {}: {}",
                            gameId, broadcastError.getMessage());
                }
            }

            logger.debug("Player action processing complete for game {}", gameId);
        }
    }

    private static Player getCurrentPlayer(PlayerActionRequest actionRequest, Game game) {
        Player currentPlayer = game.getCurrentPlayer();

        if (actionRequest == null || actionRequest.action() == null) {
            throw new BadRequestException("Action is required");
        }

        int requestAmount = actionRequest.amount() != null ? actionRequest.amount() : 0;
        if (requestAmount < 0) {
            throw new BadRequestException(
                    "Action amount cannot be negative",
                    "NEGATIVE_AMOUNT",
                    "Amount must be positive; yours: " + requestAmount);
        }

        PlayerAction action = actionRequest.action();
        if ((action == PlayerAction.BET || action == PlayerAction.RAISE) && requestAmount == 0) {
            throw new BadRequestException(
                    "Bet/raise amount must be greater than 0",
                    "AMOUNT_REQUIRED",
                    "Enter a positive amount");
        }
        if ((action == PlayerAction.BET || action == PlayerAction.RAISE)
                && requestAmount > currentPlayer.getChips()) {
            throw new BadRequestException(
                    "Action amount cannot exceed your available chips",
                    "INSUFFICIENT_CHIPS",
                    "Available: " + currentPlayer.getChips() + "; yours: " + requestAmount);
        }
        return currentPlayer;
    }
}
