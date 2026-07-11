package com.pokergame.service;

import com.pokergame.dto.internal.PlayerDecision;
import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.event.AutoAdvanceEvent;
import com.pokergame.event.StartReadyCountdownEvent;
import com.pokergame.exception.BadRequestException;
import com.pokergame.exception.UnauthorisedActionException;
import com.pokergame.exception.ResourceNotFoundException;
import com.pokergame.enums.PlayerAction;
import com.pokergame.model.Game;
import com.pokergame.model.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.*;

import com.pokergame.dto.internal.TurnOutcome;

/**
 * Service class responsible for processing player actions and game progression.
 * Handles player decisions, betting rounds, and automatic game advancement.
 */
@Service
public class PlayerActionService {

    private static final Logger logger = LoggerFactory.getLogger(PlayerActionService.class);

    @Value("${poker.round-end.display-delay-ms:0}")
    private long roundEndDelayMs = 0;

    @Value("${poker.ready-countdown-ms:30000}")
    private long readyCountdownMs = 30000;

    private final GameLifecycleService gameLifecycleService;

    private final GameStateService gameStateService;

    private final ApplicationEventPublisher eventPublisher;

    public PlayerActionService(GameLifecycleService gameLifecycleService, GameStateService gameStateService,
            ApplicationEventPublisher eventPublisher) {
        this.gameLifecycleService = gameLifecycleService;
        this.gameStateService = gameStateService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Processes a player action request and advances the game state accordingly.
     * Validates the request, processes the decision, and handles game progression.
     *
     * @param gameId        the unique identifier of the game
     * @param actionRequest the action request containing the action type and amount
     * @param playerName    the authenticated player name (from JWT Principal)
     * @throws UnauthorisedActionException if the requesting player is not the
     *                                     current player
     * @throws ResourceNotFoundException   if the game is not found
     */
    public void processPlayerAction(String gameId, PlayerActionRequest actionRequest, String playerName) {
        Game game = gameLifecycleService.getGame(gameId);
        if (game == null) {
            logger.warn("Game not found for ID: {} when trying to process player action", gameId);
            throw new ResourceNotFoundException(
                    "Game not found:");
        }

        // Synchronise on the game object to prevent concurrent modifications
        synchronized (game) {
            Player currentPlayer = getCurrentPlayer(actionRequest, game);

            logger.debug("Processing player action - Game: {}, Player: {}, Action: {}",
                    gameId, currentPlayer.getName(), actionRequest.action());
            logger.debug("Game state - Phase: {}, Current bet: {}",
                    game.getCurrentPhase(), game.getCurrentHighestBet());

            // Verify that the requesting player is the current player
            if (!currentPlayer.getName().equals(playerName)) {
                logger.warn("Player name mismatch: expected {}, got {}",
                        currentPlayer.getName(), playerName);
                throw new UnauthorisedActionException(
                        "It's not your turn. Current player is: " + currentPlayer.getName());
            }

            if (currentPlayer.getIsDisconnected()) {
                logger.warn("Disconnected player {} attempted to act in game {}", playerName, gameId);
                throw new UnauthorisedActionException("You are disconnected. Reconnect to continue your turn.");
            }

            PlayerDecision decision = new PlayerDecision(
                    actionRequest.action(),
                    actionRequest.amount() != null ? actionRequest.amount() : 0,
                    currentPlayer.getPlayerId());

            logger.debug("Processing decision: {}", decision);

            TurnOutcome outcome = game.processPlayerDecision(currentPlayer, decision);
            logger.debug("Decision processed successfully");

            // If there was a conversion, notify the player
            if (outcome.conversionMessage() != null) {
                logger.info("Sending conversion message to player {}: {}", currentPlayer.getName(), outcome.conversionMessage());
                gameStateService.sendPlayerNotification(gameId, currentPlayer.getName(), outcome.conversionMessage());
            }

            try {
                switch (outcome.type()) {
                    case NEXT_PLAYER -> {
                        gameStateService.broadcastGameState(gameId, game);
                    }
                    case PHASE_ADVANCED -> {
                        gameStateService.broadcastGameState(gameId, game);
                    }
                    case AUTO_ADVANCING -> {
                        gameStateService.broadcastAutoAdvanceNotification(gameId, game);
                        eventPublisher.publishEvent(new AutoAdvanceEvent(gameId));
                    }
                    case SHOWDOWN -> {
                        openReadyCountdownGate(gameId);
                        gameStateService.broadcastShowdownResults(gameId, game, outcome.winners(), outcome.winningsPerPlayer());
                    }
                }
            } catch (Exception e) {
                logger.error("Error in post-processing for game {} (action was successful): {}", gameId, e.getMessage(), e);
                // Re-broadcast to ensure clients have the updated state
                try {
                    gameStateService.broadcastGameState(gameId, game);
                } catch (Exception broadcastError) {
                    logger.error("Failed to re-broadcast game state for game {}: {}", gameId, broadcastError.getMessage());
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
            throw new BadRequestException("Action amount cannot be negative");
        }

        PlayerAction action = actionRequest.action();

        if ((action == PlayerAction.BET || action == PlayerAction.RAISE) && requestAmount == 0) {
            throw new BadRequestException("Bet/raise amount must be greater than 0");
        }

        if ((action == PlayerAction.BET || action == PlayerAction.RAISE)
                && requestAmount > currentPlayer.getChips()) {
            throw new BadRequestException("Action amount cannot exceed your available chips");
        }
        return currentPlayer;
    }



    private void openReadyCountdownGate(String gameId) {
        if (roundEndDelayMs <= 0) {
            gameLifecycleService.startReadyCountdown(gameId, readyCountdownMs);
            return;
        }

        eventPublisher.publishEvent(new StartReadyCountdownEvent(gameId, roundEndDelayMs, readyCountdownMs));
    }

}
