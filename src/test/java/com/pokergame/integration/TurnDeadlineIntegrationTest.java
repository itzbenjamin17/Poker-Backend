package com.pokergame.integration;

import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.enums.PlayerAction;
import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.exception.BadRequestException;
import com.pokergame.integration.support.AbstractIntegrationTestSupport;
import com.pokergame.service.GameLifecycleService;
import com.pokergame.service.GameStateService;
import com.pokergame.service.PlayerActionService;
import com.pokergame.service.RoomService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises turn deadlines at the authoritative lifecycle and public projection seam. */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TurnDeadlineIntegrationTest.TimeConfiguration.class)
class TurnDeadlineIntegrationTest extends AbstractIntegrationTestSupport {
    @Autowired GameLifecycleService lifecycle;
    @Autowired GameStateService states;
    @Autowired RoomService rooms;
    @Autowired PlayerActionService playerActionService;
    @Autowired MutableClock clock;

    @BeforeEach void resetClock() { clock.now = System.currentTimeMillis(); }

    @TestConfiguration
    static class TimeConfiguration {
        @Bean @Primary MutableClock turnTestClock() { return new MutableClock(); }
    }

    static class MutableClock extends Clock {
        long now = System.currentTimeMillis();
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(now); }
        public long millis() { return now; }
    }

    @Test
    void startingAHandPublishesOneMinuteForTheCurrentDecision() throws Exception {
        String roomName = uniqueName("TurnDeadline");
        var host = createRoom(roomName, "Host", 4);
        joinRoom(roomName, "Guest");
        long before = clock.millis();
        String roomId = host.path("roomId").asText();
        startGame(roomId, host.path("token").asText());
        var state = states.getPublicGameStateSnapshot(roomId, lifecycle.getGame(roomId));
        assertThat(state.turnDeadlineEpochMs()).isBetween(before + 60_000, clock.millis() + 60_000);
    }

    @Test
    void expiryRemovesTheActorAndAwardsTheNormalHeadsUpVictory() throws Exception {
        String name = uniqueName("TurnRemoval");
        var host = createRoom(name, "Host", 4);
        joinRoom(name, "Guest");
        String id = host.path("roomId").asText();
        startGame(id, host.path("token").asText());
        lifecycle.markPlayerReconnected(id, "Host");
        lifecycle.markPlayerReconnected(id, "Guest");
        var game = lifecycle.getGame(id);
        String actor = game.getCurrentPlayer().getName();
        long deadline = states.getPublicGameStateSnapshot(id, game).turnDeadlineEpochMs();
        clock.now = deadline;
        lifecycle.executeScheduledTask(id, ScheduledGameTask.TURN_TIMEOUT, deadline,
                game.getHandNumber(), game.getScheduledTaskGeneration(ScheduledGameTask.TURN_TIMEOUT));
        assertThat(rooms.getRoom(id).hasPlayer(actor)).isFalse();
        assertThat(game.isGameOver()).isTrue();
        String survivor = actor.equals("Host") ? "Guest" : "Host";
        assertThat(lifecycle.getGameReview(id, survivor).winner()).isEqualTo(survivor);
    }

    @Test
    void actionJustBeforeExpiryIsAcceptedAndResetsDeadlineForNextActor() throws Exception {
        String name = uniqueName("TurnTimely");
        var host = createRoom(name, "Host", 4);
        joinRoom(name, "Guest1");
        joinRoom(name, "Guest2");
        String id = host.path("roomId").asText();
        startGame(id, host.path("token").asText());
        lifecycle.markPlayerReconnected(id, "Host");
        lifecycle.markPlayerReconnected(id, "Guest1");
        lifecycle.markPlayerReconnected(id, "Guest2");

        var game = lifecycle.getGame(id);
        String actor1 = game.getCurrentPlayer().getName();
        long deadline1 = states.getPublicGameStateSnapshot(id, game).turnDeadlineEpochMs();

        // Advance clock to 1ms before deadline
        clock.now = deadline1 - 1;
        playerActionService.processPlayerAction(id, new PlayerActionRequest(PlayerAction.CALL, null), actor1);

        // Next actor should have a fresh 60s deadline from current clock
        var nextActor = game.getCurrentPlayer().getName();
        assertThat(nextActor).isNotEqualTo(actor1);
        long deadline2 = states.getPublicGameStateSnapshot(id, game).turnDeadlineEpochMs();
        assertThat(deadline2).isEqualTo(clock.now + 60_000);
    }

    @Test
    void actionAtOrAfterExpiryIsRejected() throws Exception {
        String name = uniqueName("TurnLate");
        var host = createRoom(name, "Host", 4);
        joinRoom(name, "Guest");
        String id = host.path("roomId").asText();
        startGame(id, host.path("token").asText());
        lifecycle.markPlayerReconnected(id, "Host");
        lifecycle.markPlayerReconnected(id, "Guest");

        var game = lifecycle.getGame(id);
        String actor = game.getCurrentPlayer().getName();
        long deadline = states.getPublicGameStateSnapshot(id, game).turnDeadlineEpochMs();

        // Exactly at deadline
        clock.now = deadline;
        assertThatThrownBy(() -> playerActionService.processPlayerAction(id, new PlayerActionRequest(PlayerAction.CALL, null), actor))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Turn deadline expired");

        // After deadline
        clock.now = deadline + 1000;
        assertThatThrownBy(() -> playerActionService.processPlayerAction(id, new PlayerActionRequest(PlayerAction.CALL, null), actor))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Turn deadline expired");
    }

    @Test
    void multiContenderTimeoutRemovesActorAndContinuesPlayWithFreshDeadline() throws Exception {
        String name = uniqueName("TurnMulti");
        var host = createRoom(name, "Host", 4);
        joinRoom(name, "Guest1");
        joinRoom(name, "Guest2");
        String id = host.path("roomId").asText();
        startGame(id, host.path("token").asText());
        lifecycle.markPlayerReconnected(id, "Host");
        lifecycle.markPlayerReconnected(id, "Guest1");
        lifecycle.markPlayerReconnected(id, "Guest2");

        var game = lifecycle.getGame(id);
        String actor = game.getCurrentPlayer().getName();
        int initialPot = game.getPot();
        long deadline = states.getPublicGameStateSnapshot(id, game).turnDeadlineEpochMs();

        clock.now = deadline;
        lifecycle.executeScheduledTask(id, ScheduledGameTask.TURN_TIMEOUT, deadline,
                game.getHandNumber(), game.getScheduledTaskGeneration(ScheduledGameTask.TURN_TIMEOUT));

        assertThat(rooms.getRoom(id).hasPlayer(actor)).isFalse();
        assertThat(game.isGameOver()).isFalse();
        assertThat(game.getActivePlayers()).hasSize(2);
        // Pot is preserved
        assertThat(game.getPot()).isEqualTo(initialPot);
        // Next player has fresh deadline
        var nextActor = game.getCurrentPlayer();
        assertThat(nextActor).isNotNull();
        assertThat(nextActor.getName()).isNotEqualTo(actor);
        long nextDeadline = states.getPublicGameStateSnapshot(id, game).turnDeadlineEpochMs();
        assertThat(nextDeadline).isEqualTo(clock.now + 60_000);
    }

    @Test
    void staleOrDuplicateCallbacksAreIgnored() throws Exception {
        String name = uniqueName("TurnStale");
        var host = createRoom(name, "Host", 4);
        joinRoom(name, "Guest1");
        joinRoom(name, "Guest2");
        String id = host.path("roomId").asText();
        startGame(id, host.path("token").asText());
        lifecycle.markPlayerReconnected(id, "Host");
        lifecycle.markPlayerReconnected(id, "Guest1");
        lifecycle.markPlayerReconnected(id, "Guest2");

        var game = lifecycle.getGame(id);
        String actor1 = game.getCurrentPlayer().getName();
        long staleDeadline = states.getPublicGameStateSnapshot(id, game).turnDeadlineEpochMs();
        long staleHandNumber = game.getHandNumber();
        long staleGen = game.getScheduledTaskGeneration(ScheduledGameTask.TURN_TIMEOUT);

        // Actor 1 acts timely
        clock.now += 5000;
        playerActionService.processPlayerAction(id, new PlayerActionRequest(PlayerAction.CALL, null), actor1);

        String actor2 = game.getCurrentPlayer().getName();
        assertThat(actor2).isNotEqualTo(actor1);

        // Stale callback for Actor 1 fires now
        clock.now = staleDeadline;
        lifecycle.executeScheduledTask(id, ScheduledGameTask.TURN_TIMEOUT, staleDeadline, staleHandNumber, staleGen);

        // Actor 2 should NOT be removed by stale callback
        assertThat(rooms.getRoom(id).hasPlayer(actor2)).isTrue();
        assertThat(game.getCurrentPlayer().getName()).isEqualTo(actor2);
    }

    @Test
    void raiseReturningToPlayerReceivesFreshSixtySeconds() throws Exception {
        String name = uniqueName("TurnRaise");
        var host = createRoom(name, "Host", 4);
        joinRoom(name, "Guest");
        String id = host.path("roomId").asText();
        startGame(id, host.path("token").asText());
        lifecycle.markPlayerReconnected(id, "Host");
        lifecycle.markPlayerReconnected(id, "Guest");

        var game = lifecycle.getGame(id);
        // In heads-up, small blind / button acts first pre-flop (Host or Guest)
        String firstActor = game.getCurrentPlayer().getName();
        clock.now += 20_000; // First actor took 20s
        playerActionService.processPlayerAction(id, new PlayerActionRequest(PlayerAction.CALL, null), firstActor);

        // Big blind checks or raises
        String secondActor = game.getCurrentPlayer().getName();
        clock.now += 15_000; // Second actor took 15s
        playerActionService.processPlayerAction(id, new PlayerActionRequest(PlayerAction.RAISE, 40), secondActor);

        // Action returns to firstActor facing the raise
        assertThat(game.getCurrentPlayer().getName()).isEqualTo(firstActor);
        long returnedDeadline = states.getPublicGameStateSnapshot(id, game).turnDeadlineEpochMs();
        // Crucial requirement: fresh 60s, earlier thinking time did not reduce new allowance
        assertThat(returnedDeadline).isEqualTo(clock.now + 60_000);
    }

    @Test
    void noTurnDeadlineDuringShowdown() throws Exception {
        String name = uniqueName("TurnShowdown");
        var host = createRoom(name, "Host", 4);
        joinRoom(name, "Guest");
        String id = host.path("roomId").asText();
        startGame(id, host.path("token").asText());
        lifecycle.markPlayerReconnected(id, "Host");
        lifecycle.markPlayerReconnected(id, "Guest");

        var game = lifecycle.getGame(id);
        String firstActor = game.getCurrentPlayer().getName();
        // First actor folds -> hand immediately enters SHOWDOWN with 1 winner
        playerActionService.processPlayerAction(id, new PlayerActionRequest(PlayerAction.FOLD, null), firstActor);

        var state = states.getPublicGameStateSnapshot(id, game);
        assertThat(state.turnDeadlineEpochMs()).isNull();
    }
}
