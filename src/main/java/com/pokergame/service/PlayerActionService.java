package com.pokergame.service;

import com.pokergame.dto.internal.PlayerDecision;
import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.event.AutoAdvanceEvent;
import com.pokergame.event.StartNewHandEvent;
import com.pokergame.exception.BadRequestException;
import com.pokergame.exception.UnauthorisedActionException;
import com.pokergame.exception.ResourceNotFoundException;
import com.pokergame.enums.PlayerAction;
import com.pokergame.model.Game;
import com.pokergame.model.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service class responsible for processing player actions and game progression.
 * Handles player decisions, betting rounds, and automatic game advancement.
 */
@Service
public class PlayerActionService {

    private static final Logger logger = LoggerFactory.getLogger(PlayerActionService.class);
    private static final long ROUND_END_DELAY_MS = 5000;

    private final GameLifecycleService gameLifecycleService;

    private final GameStateService gameStateService;

    private final ApplicationEventPublisher eventPublisher;

    public PlayerActionService(GameLifecycleService gameLifecycleService, GameStateService gameStateService,
            ApplicationEventPublisher eventPublisher) {
        this.gameLifecycleService = gameLifecycleService;
        this.gameStateService = gameStateService;
        this.eventPublisher = eventPublisher;
    }

    private final Map<String, Set<String>> playersWhoActedInInitialTurn = new ConcurrentHashMap<>();

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
            Player currentPlayer = game.getCurrentPlayer();

            if (actionRequest == null || actionRequest.action() == null) {
                throw new BadRequestException("Action is required");
            }

            int requestAmount = actionRequest.amount() != null ? actionRequest.amount() : 0;
            if (requestAmount < 0) {
                throw new BadRequestException("Action amount cannot be negative");
            }

            PlayerAction action = actionRequest.action();

            if ((action == PlayerAction.BET || action == PlayerAction.RAISE) && requestAmount <= 0) {
                throw new BadRequestException("Bet/raise amount must be greater than 0");
            }

            if ((action == PlayerAction.BET || action == PlayerAction.RAISE)
                    && requestAmount > currentPlayer.getChips()) {
                throw new BadRequestException("Action amount cannot exceed your available chips");
            }

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

            PlayerDecision decision = new PlayerDecision(
                    actionRequest.action(),
                    actionRequest.amount() != null ? actionRequest.amount() : 0,
                    currentPlayer.getPlayerId());

            logger.debug("Processing decision: {}", decision);

            // Process the decision first - this is the critical operation that must succeed
            String conversionMessage = game.processPlayerDecision(currentPlayer, decision);
            logger.debug("Decision processed successfully");

            // If there was a conversion, notify the player
            if (conversionMessage != null) {
                logger.info("Sending conversion message to player {}: {}", currentPlayer.getName(), conversionMessage);
                gameStateService.sendPlayerNotification(gameId, currentPlayer.getName(), conversionMessage);
            }

            // Immediately check and advance to showdown if everyone else just folded.
            if (game.isHandOver()) {
                advanceGame(gameId);
                return;
            }

            // After successful processing, handle game progression and broadcasting
            // This is done in a try-catch to ensure that even if broadcasting fails,
            // the action itself was successful
            try {
                // Track who has acted in the initial turn
                Set<String> actedPlayers = playersWhoActedInInitialTurn.computeIfAbsent(gameId, k -> new HashSet<>());
                actedPlayers.add(currentPlayer.getPlayerId());

                // Check if everyone has had their initial turn
                List<Player> playersWhoShouldAct = game.getActivePlayers().stream()
                        .filter(p -> !p.getHasFolded() && !p.getIsAllIn())
                        .toList();

                boolean everyoneHasActed = playersWhoShouldAct.stream()
                        .allMatch(p -> actedPlayers.contains(p.getPlayerId()));

                if (everyoneHasActed && !game.isBettingRoundComplete()) {
                    game.setEveryoneHasHadInitialTurn(true);
                }

                // Broadcast game state after player action
                gameStateService.broadcastGameState(gameId, game);

                logger.debug("Checking if betting round is complete for game {}...", gameId);
                if (game.isBettingRoundComplete()) {
                    logger.info("Betting round complete for game {}, advancing game", gameId);
                    playersWhoActedInInitialTurn.remove(gameId);
                    advanceGame(gameId);
                } else {
                    logger.debug("Betting round not complete for game {}, moving to next player", gameId);
                    game.nextPlayer();
                    gameStateService.broadcastGameState(gameId, game);
                }
            } catch (Exception e) {
                logger.error("Error in post-processing for game {} (action was successful): {}", gameId, e.getMessage(),
                        e);
                // Re-broadcast to ensure clients have the updated state
                try {
                    gameStateService.broadcastGameState(gameId, game);
                } catch (Exception broadcastError) {
                    logger.error("Failed to re-broadcast game state for game {}: {}", gameId,
                            broadcastError.getMessage());
                }
            }

            logger.debug("Player action processing complete for game {}", gameId);
        }
    }

    /**
     * Advances the game to the next phase or conducts showdown if hand is over.
     * Handles progression through betting rounds (PRE_FLOP → FLOP → TURN → RIVER →
     * SHOWDOWN)
     * and manages game state transitions. Automatically advances when all players
     * are all-in.
     *
     * @param gameId the unique identifier of the game to advance
     */
    private void advanceGame(String gameId) {
        Game game = gameLifecycleService.getGame(gameId);
        if (game == null) {
            logger.warn("Cannot advance game {} - game no longer exists", gameId);
            return;
        }
        logger.info("Advancing game {} from phase: {}", gameId, game.getCurrentPhase());

        if (game.isHandOver()) {
            logger.info("Hand is over for game {}, conducting showdown", gameId);
            int potBeforeDistribution = game.getPot();
            List<Player> winners = game.conductShowdown();
            logger.info("Showdown complete for game {} | Winners: {}",
                    gameId, winners.stream().map(Player::getName).toList());

            int winningsPerPlayer = winners.isEmpty() ? 0 : potBeforeDistribution / winners.size();
            gameStateService.broadcastShowdownResults(gameId, game, winners, winningsPerPlayer);

            // Delay before starting the new hand to allow winner display
            eventPublisher.publishEvent(new StartNewHandEvent(gameId, ROUND_END_DELAY_MS));
            return;
        }

        // Check if we need to auto-advance because of an all-in situation
        long playersAbleToAct = game.getActivePlayers().stream()
                .filter(p -> !p.getHasFolded() && !p.getIsAllIn())
                .count();

        logger.debug("Game {} status | Players able to act: {} | Betting round complete: {}",
                gameId, playersAbleToAct, game.isBettingRoundComplete());

        // Auto-advance if the betting round is complete AND most players are all-in
        if (game.isBettingRoundComplete() && playersAbleToAct <= 1) {
            logger.info("All-in situation detected for game {}, auto-advancing to showdown", gameId);
            gameStateService.broadcastAutoAdvanceNotification(gameId, game);
            eventPublisher.publishEvent(new AutoAdvanceEvent(gameId));
            return;
        }

        // Normal advancement logic
        switch (game.getCurrentPhase()) {
            case PRE_FLOP:
                logger.info("Game {} advancing to FLOP phase", gameId);
                game.dealFlop();
                gameStateService.broadcastGameState(gameId, game);
                break;
            case FLOP:
                logger.info("Game {} advancing to TURN phase", gameId);
                game.dealTurn();
                gameStateService.broadcastGameState(gameId, game);
                break;
            case TURN:
                logger.info("Game {} advancing to RIVER phase", gameId);
                game.dealRiver();
                gameStateService.broadcastGameState(gameId, game);
                break;
            case RIVER:
                logger.info("RIVER betting complete for game {}, conducting showdown", gameId);
                int potBeforeDistribution = game.getPot();
                List<Player> winners = game.conductShowdown();
                logger.info("Showdown complete for game {} | Winners: {}",
                        gameId, winners.stream().map(Player::getName).toList());

                int winningsPerPlayer = winners.isEmpty() ? 0 : potBeforeDistribution / winners.size();
                gameStateService.broadcastShowdownResults(gameId, game, winners, winningsPerPlayer);

                // Delay before starting the new hand, on a different thread
                eventPublisher.publishEvent(new StartNewHandEvent(gameId, ROUND_END_DELAY_MS));
                break;
            case SHOWDOWN:
                logger.warn("Game {} is already in SHOWDOWN phase", gameId);
                break;
        }
    }

}
