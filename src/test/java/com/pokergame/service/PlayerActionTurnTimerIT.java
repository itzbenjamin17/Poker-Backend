package com.pokergame.service;

import com.pokergame.util.RoomCoordinator;

import static com.pokergame.testutil.TestRoomLocks.withRoomLock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.pokergame.config.MdcTaskDecorator;
import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.enums.PlayerAction;
import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.exception.UnauthorisedActionException;
import com.pokergame.model.Game;
import com.pokergame.testutil.MutableClock;
import com.pokergame.testutil.TestRoomFixtures;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.ActiveProfiles;

/**
 * Verifies in memory mode that rejected player actions keep the active turn timer scheduled and
 * allow the real scheduled callback to time out the actor when the deadline elapses.
 */
@Tag("integration")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.main.allow-bean-definition-overriding=true",
                "poker.persistence.enabled=false",
                "poker.rate-limiting.enabled=false",
                "poker.lifecycle.round-end-display-delay-ms=0",
                "poker.lifecycle.ready-countdown-ms=600000"
        })
@ActiveProfiles("test")
@Import(PlayerActionTurnTimerIT.TimerTestConfig.class)
@DisplayName("Player action turn timer retention integration")
class PlayerActionTurnTimerIT {

    private static final long T0 = 1_900_000_000_000L;
    private static final Duration ASYNC_TIMEOUT = Duration.ofSeconds(3);

    @Autowired
    private RoomService roomService;

    @Autowired
    private GameLifecycleService gameLifecycleService;

    @Autowired
    private PlayerActionService playerActionService;

    @Autowired
    private RoomCoordinator roomCoordinator;

    @Autowired
    private MutableClock clock;

    @Autowired
    private ControllableTaskScheduler taskScheduler;

    private final Set<String> trackedRoomIds = new HashSet<>();
    private TestRoomFixtures roomFixtures;

    @BeforeEach
    void setUp() {
        clock.setMillis(T0);
        taskScheduler.clearTrackedTasks();
        roomFixtures = new TestRoomFixtures(roomService, roomCoordinator, trackedRoomIds);
    }

    @AfterEach
    void tearDown() {
        taskScheduler.clearTrackedTasks();
        for (String roomId : List.copyOf(trackedRoomIds)) {
            withRoomLock(roomCoordinator, roomId, () -> {
                gameLifecycleService.evictGame(roomId);
                roomService.destroyRoom(roomId);
                return null;
            });
        }
        trackedRoomIds.clear();
    }

    @Test
    @DisplayName("invalid CHECK when a call is owed rejects the action without cancelling the turn timer")
    void invalidCheckKeepsTurnTimerScheduledAndTimesOutActor() {
        String roomId = roomFixtures.createAndConnectRoom("Timer Retain Room", "Host", "Alice", "Bob");
        gameLifecycleService.createGameFromRoom(roomId);

        String actor = withRoomLock(roomCoordinator, roomId, () -> {
            Game game = gameLifecycleService.getGame(roomId);
            return game.getCurrentPlayer().getName();
        });
        Long turnDeadlineBefore = withRoomLock(roomCoordinator, roomId, () ->
                gameLifecycleService.getGame(roomId).getScheduledTaskDeadline(ScheduledGameTask.TURN_TIMEOUT));
        assertThat(turnDeadlineBefore).isNotNull();

        assertThatThrownBy(() -> playerActionService.processPlayerAction(
                roomId, new PlayerActionRequest(PlayerAction.CHECK, null), actor))
                .isInstanceOf(UnauthorisedActionException.class)
                .satisfies(ex -> assertThat(((UnauthorisedActionException) ex).actionErrorCode())
                        .isEqualTo("CHECK_NOT_AVAILABLE"));

        withRoomLock(roomCoordinator, roomId, () -> {
            Game game = gameLifecycleService.getGame(roomId);
            assertThat(game.getCurrentPlayer().getName()).isEqualTo(actor);
            assertThat(game.getScheduledTaskDeadline(ScheduledGameTask.TURN_TIMEOUT)).isEqualTo(turnDeadlineBefore);
            return null;
        });

        clock.setMillis(turnDeadlineBefore);
        taskScheduler.triggerDueTasks(clock.instant());

        await().atMost(ASYNC_TIMEOUT).untilAsserted(() -> withRoomLock(roomCoordinator, roomId, () -> {
            Game game = gameLifecycleService.getGame(roomId);
            assertThat(roomService.getRoom(roomId).hasPlayer(actor)).isFalse();
            assertThat(game.findPlayerByName(actor)).isNull();
            assertThat(game.getCurrentPlayer()).isNotNull();
            assertThat(game.getCurrentPlayer().getName()).isNotEqualTo(actor);
            return null;
        }));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TimerTestConfig {

        @Bean
        @Primary
        MutableClock timerTestClock() {
            return new MutableClock(T0);
        }

        @Bean(name = "taskScheduler")
        @Primary
        ControllableTaskScheduler taskScheduler() {
            ControllableTaskScheduler scheduler = new ControllableTaskScheduler();
            scheduler.setPoolSize(4);
            scheduler.setThreadNamePrefix("TestTaskScheduler-");
            scheduler.setTaskDecorator(new MdcTaskDecorator());
            scheduler.initialize();
            return scheduler;
        }
    }

    static class ControllableTaskScheduler extends ThreadPoolTaskScheduler {

        private record TrackedTask(Instant startTime, Runnable task, ScheduledFuture<?> future) {
        }

        private final List<TrackedTask> trackedTasks = new CopyOnWriteArrayList<>();

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
            ScheduledFuture<?> future = super.schedule(task, startTime);
            if (future != null) {
                trackedTasks.add(new TrackedTask(startTime, task, future));
            }
            return future;
        }

        void triggerDueTasks(Instant now) {
            for (TrackedTask entry : List.copyOf(trackedTasks)) {
                if (!entry.startTime().isAfter(now)) {
                    trackedTasks.remove(entry);
                    if (entry.future().cancel(false)) {
                        super.execute(entry.task());
                    }
                }
            }
        }

        void clearTrackedTasks() {
            for (TrackedTask entry : List.copyOf(trackedTasks)) {
                entry.future().cancel(false);
            }
            trackedTasks.clear();
        }
    }
}
