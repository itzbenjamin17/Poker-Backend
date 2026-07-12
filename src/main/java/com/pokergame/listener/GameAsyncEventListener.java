package com.pokergame.listener;

import com.pokergame.event.AutoAdvanceEvent;
import com.pokergame.event.GameCleanupEvent;
import com.pokergame.event.StartNewHandEvent;
import com.pokergame.event.StartReadyCountdownEvent;
import com.pokergame.service.GameLifecycleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;


/**
 * Event listener responsible for handling asynchronous game events and timing.
 * <p>
 * It translates domain events into durable scheduling commands. Runtime timers
 * are owned by {@link GameLifecycleService} so their deadlines can be persisted
 * and rebuilt after a restart.
 * </p>
 */
@Component
public class GameAsyncEventListener {

    private static final Logger logger = LoggerFactory.getLogger(GameAsyncEventListener.class);

    private final GameLifecycleService gameLifecycleService;

    /**
     * Constructs a new GameAsyncEventListener.
     *
     * @param gameLifecycleService service for managing game lifecycle (starting
     *                             hands, clean-up)
     */
    public GameAsyncEventListener(GameLifecycleService gameLifecycleService) {
        this.gameLifecycleService = gameLifecycleService;
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
        gameLifecycleService.scheduleNewHand(event.gameId(), event.delay());
    }

    /**
     * Opens the post-round ready countdown gate using a configured delay.
     * Default behaviour is immediate open on showdown reveal.
     *
     * @param event event containing game id, display delay, and countdown duration
     */
    @EventListener
    public void handleStartReadyCountdown(StartReadyCountdownEvent event) {
        logger.info("Scheduling ready countdown for game {} in {}ms", event.gameId(), event.delayMs());

        gameLifecycleService.scheduleReadyCountdownOpen(event.gameId(), event.delayMs());
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

        gameLifecycleService.scheduleGameCleanup(event.gameId(), event.delay());
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
        gameLifecycleService.scheduleAutoAdvance(event.gameId());
    }

}
