package com.pokergame.service;

import static java.util.Objects.requireNonNull;

import com.pokergame.dto.internal.PlayerDecision;
import com.pokergame.dto.internal.TurnOutcome;
import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.enums.GamePhase;
import com.pokergame.enums.ResponseMessage;
import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.exception.BadRequestException;
import com.pokergame.exception.ResourceNotFoundException;
import com.pokergame.exception.UnauthorisedActionException;
import com.pokergame.model.Game;
import com.pokergame.model.Player;
import com.pokergame.persistence.transaction.DurableMutation;
import com.pokergame.persistence.transaction.GameId;
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
    private final ActiveGameRegistry activeGames;
    private final GameCompletionHandler completionHandler;
    private final GameTaskScheduler taskScheduler;
    private final GameReadinessCoordinator readinessCoordinator;
    private final GameStateService gameStateService;
    private final Clock clock;

    PlayerActionService(
            ActiveGameRegistry activeGames,
            GameCompletionHandler completionHandler,
            GameTaskScheduler taskScheduler,
            GameReadinessCoordinator readinessCoordinator,
            GameStateService gameStateService,
            Clock clock) {
        this.activeGames = activeGames;
        this.completionHandler = completionHandler;
        this.taskScheduler = taskScheduler;
        this.readinessCoordinator = readinessCoordinator;
        this.gameStateService = gameStateService;
        this.clock = clock;
    }

    /**
     * Validates a player's requested poker action, applies it to the game, resets the inactivity
     * timer, saves the updated state, and broadcasts the turn or showdown result after saving
     * completes.
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
    @DurableMutation
    public void processPlayerAction(@GameId String gameId, PlayerActionRequest actionRequest, String playerName) {
        requireNonNull(gameId, "gameId must not be null");
        requireNonNull(playerName, "playerName must not be null");
        requireNonNull(actionRequest, "actionRequest must not be null");
        completionHandler.rejectExpiredActivity(gameId);

        Game game = activeGames.get(gameId);
        if (game == null) {
            logger.warn("Game not found for ID: {} when trying to process player action", gameId);
            throw new ResourceNotFoundException("Game not found");
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
        if (game.isAutoAdvancing()) {
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

        Player currentPlayer = game.getCurrentPlayer();

        if (!currentPlayer.getName().equals(playerName)) {
            logger.warn("Player name mismatch: expected {}, got {}", currentPlayer.getName(), playerName);
            throw new UnauthorisedActionException(
                    "It's not your turn. Current player is: " + currentPlayer.getName(),
                    "NOT_YOUR_TURN",
                    "Wait for your turn");
        }
        if (currentPlayer.isDisconnected()) {
            logger.warn("Disconnected player {} attempted to act in game {}", playerName, gameId);
            throw new UnauthorisedActionException(
                    "You are disconnected. Reconnect to continue your turn.",
                    "DISCONNECTED",
                    "Reconnect to act");
        }

        PlayerDecision decision = new PlayerDecision(
                actionRequest.action(),
                actionRequest.amount(),
                currentPlayer.getPlayerId());

        TurnOutcome outcome = game.processPlayerDecision(currentPlayer, decision);
        taskScheduler.cancelTask(game, ScheduledGameTask.TURN_TIMEOUT);
        taskScheduler.resetGameInactivity(game);

        if (outcome.playerActionNotice() != null) {
            logger.info("Sending conversion message to player {}: {}",
                    currentPlayer.getName(), outcome.playerActionNotice());
            gameStateService.sendPrivatePlayerNotification(
                    gameId, currentPlayer.getName(), outcome.playerActionNotice(), ResponseMessage.PLAYER_NOTIFICATION);
        }

        readinessCoordinator.applyTurnOutcomeAndOpenReadyGateIfNeeded(game, outcome, true);
    }
}
