package com.pokergame.listener;

import com.pokergame.enums.GamePhase;
import com.pokergame.model.Game;
import com.pokergame.model.Player;
import com.pokergame.service.GameLifecycleService;
import com.pokergame.service.GameStateService;
import com.pokergame.event.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Event listener responsible for handling asynchronous game events and timing.
 * <p>
 * This class replaces blocking calls (like {@code Thread.sleep}) with
 * non-blocking
 * scheduled tasks using Spring's {@link TaskScheduler}. It manages the pacing
 * of the game by handling delays between hands, game clean-up, and the
 * automatic
 * progression of the game during all-in situations.
 * </p>
 */
@Component
public class GameAsyncEventListener {

    private static final Logger logger = LoggerFactory.getLogger(GameAsyncEventListener.class);
    private static final long ROUND_END_DELAY_MS = 5000;

    private final GameLifecycleService gameLifecycleService;
    private final GameStateService gameStateService;
    private final TaskScheduler taskScheduler;

    /**
     * Constructs a new GameAsyncEventListener.
     *
     * @param gameLifecycleService service for managing game lifecycle (starting
     *                             hands, clean-up)
     * @param gameStateService     service for broadcasting game updates to players
     * @param taskScheduler        the scheduler used to execute tasks in the future
     *                             without blocking threads
     */
    public GameAsyncEventListener(GameLifecycleService gameLifecycleService,
            GameStateService gameStateService,
            @Qualifier("taskScheduler") TaskScheduler taskScheduler) {
        this.gameLifecycleService = gameLifecycleService;
        this.gameStateService = gameStateService;
        this.taskScheduler = taskScheduler;
    }

    /**
     * Handles the {@link StartNewHandEvent} by scheduling the start of a new hand
     * after a specified delay.
     *
     * @param event the event containing the game ID and the delay in milliseconds
     */
    @EventListener
    public void handleStartNewHandDelay(StartNewHandEvent event) {
        logger.info("Scheduling new hand for game {} in {}ms", event.gameId(), event.delay());

        taskScheduler.schedule(() -> {
            try {
                gameLifecycleService.startNewHand(event.gameId());
            } catch (Exception e) {
                logger.error("Error starting new hand for game {}: {}", event.gameId(), e.getMessage());
            }
        }, Instant.now().plusMillis(event.delay()));
    }

    /**
     * Handles the {@link GameCleanupEvent} by scheduling the destruction of game
     * resources.
     * <p>
     * This ensures that when a game ends (e.g. only one player left), the "Game
     * Over" state
     * persists for a few seconds before the room is destroyed.
     * </p>
     *
     * @param event the event containing the game ID and the delay in milliseconds
     */
    @EventListener
    public void handleGameEndCleanup(GameCleanupEvent event) {
        logger.info("Scheduling cleanup for game {} in {}ms", event.gameId(), event.delay());

        taskScheduler.schedule(() -> {
            try {
                gameLifecycleService.performGameCleanup(event.gameId());
            } catch (Exception e) {
                logger.error("Error cleaning up game {}: {}", event.gameId(), e.getMessage());
            }
        }, Instant.now().plusMillis(event.delay()));
    }

    /**
     * Initiates the auto-advance sequence when all active players are all-in.
     * <p>
     * Unlike simple delays, this event triggers a chain of scheduled tasks that
     * deal the remaining community cards (Flop, Turn, River) one by one with visual
     * delays,
     * culminating in a showdown.
     * </p>
     *
     * @param event the event containing the game ID
     */
    @EventListener
    public void handleAutoAdvanceToShowdown(AutoAdvanceEvent event) {
        // Start the chain immediately
        scheduleNextAutoAdvanceStep(event.gameId());
    }

    /**
     * Recursively schedules the next step in the auto-advance sequence.
     * <p>
     * This method checks the current game phase and schedules the appropriate next
     * action
     * (e.g. dealing the Turn after the Flop) to occur after a delay. If the game
     * reaches
     * the end (Showdown), it processes the winners and schedules the next hand.
     * </p>
     *
     * @param gameId the unique identifier of the game to advance
     */
    private void scheduleNextAutoAdvanceStep(String gameId) {
        Game game = gameLifecycleService.getGame(gameId);
        if (game == null)
            return;

        // Delay between steps (e.g. between Flop and Turn)
        long delay = 4000;

        taskScheduler.schedule(() -> {
            try {
                // Re-fetching the game state inside the scheduled thread ensures we have the
                // latest state
                Game currentGame = gameLifecycleService.getGame(gameId);
                if (currentGame == null)
                    return;

                GamePhase currentPhase = currentGame.getCurrentPhase();
                boolean sequenceComplete = false;

                if (currentPhase == GamePhase.PRE_FLOP) {
                    currentGame.dealFlop();
                    gameStateService.broadcastGameStateWithAutoAdvance(gameId, currentGame, true, "Dealing flop...");
                } else if (currentPhase == GamePhase.FLOP) {
                    currentGame.dealTurn();
                    gameStateService.broadcastGameStateWithAutoAdvance(gameId, currentGame, true, "Dealing turn...");
                } else if (currentPhase == GamePhase.TURN) {
                    currentGame.dealRiver();
                    gameStateService.broadcastGameStateWithAutoAdvance(gameId, currentGame, true, "Dealing river...");
                } else {
                    // We are at the end (RIVER or SHOWDOWN)
                    int potBeforeDistribution = currentGame.getPot();
                    List<Player> winners = currentGame.conductShowdown();
                    int winningsPerPlayer = winners.isEmpty() ? 0 : potBeforeDistribution / winners.size();

                    gameStateService.broadcastShowdownResults(gameId, currentGame, winners, winningsPerPlayer);
                    gameStateService.broadcastAutoAdvanceComplete(gameId, currentGame);

                    // Schedule the final new hand start
                    taskScheduler.schedule(() -> gameLifecycleService.startNewHand(gameId),
                            Instant.now().plusMillis(ROUND_END_DELAY_MS));
                    sequenceComplete = true;
                }

                // If the sequence isn't done, schedule the next step recursively
                if (!sequenceComplete) {
                    scheduleNextAutoAdvanceStep(gameId);
                }

            } catch (Exception e) {
                logger.error("Error during auto-advance step for game {}: {}", gameId, e.getMessage());
            }
        }, Instant.now().plusMillis(delay));
    }
}