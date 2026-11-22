package com.pokergame.service;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.pokergame.config.GameTimingProperties;
import com.pokergame.dto.response.PublicGameStateResponse;
import com.pokergame.model.Game;
import com.pokergame.testutil.MutableClock;
import com.pokergame.testutil.TestRoomLocks;
import com.pokergame.util.RoomCoordinator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.TaskScheduler;

/**
 * Wires the real lifecycle collaborators (registry, timers, hand progression, readiness,
 * departures, completion, and {@link GameStateService}) over mocked room, broker, and task-scheduler
 * edges so lifecycle tests assert the messages that reach the broker.
 */
final class LifecycleTestFixture {
    final RoomCoordinator roomCoordinator = new RoomCoordinator();
    final ActiveGameRegistry activeGames = new ActiveGameRegistry(roomCoordinator);
    final GameStateService gameStateService;
    final GameTimerRegistry timerRegistry;
    final GameTaskScheduler scheduler;
    final GameCompletionHandler completionHandler;
    final HandProgressionService handProgression;
    final GameReadinessCoordinator readinessCoordinator;
    final PlayerDepartureHandler departureHandler;
    final GameLifecycleService gameLifecycleService;
    private final SimpMessagingTemplate messagingTemplate;

    LifecycleTestFixture(
            RoomService roomService,
            SimpMessagingTemplate messagingTemplate,
            TaskScheduler taskScheduler,
            MutableClock clock,
            GameTimingProperties timings) {
        this.messagingTemplate = messagingTemplate;
        gameStateService = new GameStateService(roomService, messagingTemplate, clock);
        timerRegistry = new GameTimerRegistry(taskScheduler, clock);
        AtomicReference<GameLifecycleService> lifecycleRef = new AtomicReference<>();
        @SuppressWarnings("unchecked")
        ObjectProvider<GameLifecycleService> lifecycleProvider = mock(ObjectProvider.class);
        lenient().when(lifecycleProvider.getObject()).thenAnswer(_ -> lifecycleRef.get());
        scheduler = new GameTaskScheduler(
                activeGames, timerRegistry, roomService, roomCoordinator, clock, timings, lifecycleProvider);
        completionHandler = new GameCompletionHandler(
                activeGames, roomService, gameStateService, timerRegistry, scheduler, clock, timings);
        handProgression = new HandProgressionService(
                activeGames, timerRegistry, scheduler, completionHandler, gameStateService, timings);
        readinessCoordinator = new GameReadinessCoordinator(
                activeGames, gameStateService, timerRegistry, scheduler,
                completionHandler, handProgression, clock, timings);
        departureHandler = new PlayerDepartureHandler(
                roomService, gameStateService, completionHandler,
                readinessCoordinator, timerRegistry, activeGames, clock, timings);
        gameLifecycleService = new GameLifecycleService(
                roomService, activeGames, timerRegistry, scheduler,
                completionHandler, readinessCoordinator, departureHandler,
                roomCoordinator, clock, timings);
        lifecycleRef.set(gameLifecycleService);
    }

    /** Runs {@code action} under the room lock for {@code roomId} and returns its result. */
    <T> T withRoomLock(String roomId, Supplier<T> action) {
        return TestRoomLocks.withRoomLock(roomCoordinator, roomId, action);
    }

    /** Runs {@code action} under the room lock for {@code roomId}. */
    void withRoomLock(String roomId, Runnable action) {
        TestRoomLocks.runWithRoomLock(roomCoordinator, roomId, action);
    }

    /** Returns the live game for {@code roomId}, read under its room lock. */
    Game getGameLocked(String roomId) {
        return withRoomLock(roomId, () -> gameLifecycleService.getGame(roomId));
    }

    /** Returns every public game-state message broadcast on {@code /game/{gameId}}, in send order. */
    List<PublicGameStateResponse> publicBroadcasts(String gameId) {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate, atLeast(0)).convertAndSend(eq("/game/" + gameId), captor.capture());
        return captor.getAllValues().stream()
                .filter(PublicGameStateResponse.class::isInstance)
                .map(PublicGameStateResponse.class::cast)
                .toList();
    }

    /** Returns the broadcasts on {@code /game/{gameId}} that carry a game-end summary. */
    List<PublicGameStateResponse> gameEndBroadcasts(String gameId) {
        return publicBroadcasts(gameId).stream().filter(response -> response.gameEnd() != null).toList();
    }

    /** Forgets every message sent so far so later assertions only see new traffic. */
    void clearBroadcasts() {
        clearInvocations(messagingTemplate);
    }
}
