package com.pokergame.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.dto.response.GameEndResponse;
import com.pokergame.enums.PlayerAction;
import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.exception.BadRequestException;
import com.pokergame.integration.support.AbstractIntegrationTestSupport;
import com.pokergame.model.Game;
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

/**
 * Integration tests for Ticket 03: Abandon inactive games after 15 minutes.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(GameInactivityIntegrationTest.TimeConfiguration.class)
class GameInactivityIntegrationTest extends AbstractIntegrationTestSupport {

    @Autowired GameLifecycleService lifecycle;
    @Autowired GameStateService states;
    @Autowired RoomService rooms;
    @Autowired PlayerActionService playerActionService;
    @Autowired MutableClock clock;

    private static final long FIFTEEN_MINUTES_MS = 15 * 60_000L;

    @BeforeEach
    void resetClock() {
        clock.now = 1_900_000_000_000L;
    }

    @TestConfiguration
    static class TimeConfiguration {
        @Bean
        @Primary
        MutableClock gameInactivityTestClock() {
            return new MutableClock();
        }
    }

    static class MutableClock extends Clock {
        long now = 1_900_000_000_000L;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(now); }
        public long millis() { return now; }
    }

    @Test
    void startingGameReplacesLobbyDeadlineWithFifteenMinuteGameDeadline() throws Exception {
        String name = uniqueName("GameInactivityStart");
        JsonNode host = createRoom(name, "Host", 4);
        joinRoom(name, "Guest");
        String roomId = host.path("roomId").asText();
        String hostToken = host.path("token").asText();

        long startTime = clock.millis() + 5 * 60_000L;
        clock.now = startTime;
        startGame(roomId, hostToken);

        // Room state has 15-minute deadline
        var room = rooms.getRoom(roomId);
        assertThat(room.getInactivityDeadlineEpochMs()).isEqualTo(startTime + FIFTEEN_MINUTES_MS);

        // Game public snapshot has 15-minute deadline
        var game = lifecycle.getGame(roomId);
        var snapshot = states.getPublicGameStateSnapshot(roomId, game);
        assertThat(snapshot.inactivityDeadlineEpochMs()).isEqualTo(startTime + FIFTEEN_MINUTES_MS);
    }

    @Test
    void deliberatePokerActionResetsInactivityDeadline() throws Exception {
        String name = uniqueName("ActionReset");
        JsonNode host = createRoom(name, "Host", 4);
        joinRoom(name, "Guest");
        String roomId = host.path("roomId").asText();
        String hostToken = host.path("token").asText();

        startGame(roomId, hostToken);
        lifecycle.markPlayerReconnected(roomId, "Host");
        lifecycle.markPlayerReconnected(roomId, "Guest");

        var game = lifecycle.getGame(roomId);
        String actor = game.getCurrentPlayer().getName();

        // Advance 30 seconds (within turn timer limit)
        long actionTime = clock.now + 30_000L;
        clock.now = actionTime;

        // Actor checks or calls
        var legal = game.getLegalActionsFor(game.getCurrentPlayer());
        PlayerAction action = legal.contains(PlayerAction.CHECK) ? PlayerAction.CHECK : PlayerAction.CALL;
        playerActionService.processPlayerAction(roomId, new PlayerActionRequest(action, 0), actor);

        // Inactivity deadline reset to actionTime + 15 minutes
        assertThat(rooms.getRoom(roomId).getInactivityDeadlineEpochMs()).isEqualTo(actionTime + FIFTEEN_MINUTES_MS);
        assertThat(states.getPublicGameStateSnapshot(roomId, game).inactivityDeadlineEpochMs())
                .isEqualTo(actionTime + FIFTEEN_MINUTES_MS);
    }

    @Test
    void readinessChangeByEligiblePlayerResetsInactivityDeadline() throws Exception {
        String name = uniqueName("ReadyReset");
        JsonNode host = createRoom(name, "Host", 4);
        joinRoom(name, "Guest");
        joinRoom(name, "Guest2");
        String roomId = host.path("roomId").asText();
        String hostToken = host.path("token").asText();

        startGame(roomId, hostToken);
        lifecycle.markPlayerReconnected(roomId, "Host");
        lifecycle.markPlayerReconnected(roomId, "Guest");
        lifecycle.markPlayerReconnected(roomId, "Guest2");
        var game = lifecycle.getGame(roomId);

        // Open ready countdown
        long readyTime = clock.now + 5 * 60_000L;
        clock.now = readyTime;
        game.openReadyCountdown(clock.now + 600_000L);

        // Advance 2 minutes during ready countdown
        long markReadyTime = clock.now + 2 * 60_000L;
        clock.now = markReadyTime;

        // Player marks ready
        lifecycle.markPlayerReadyForNextHand(roomId, "Host");
        assertThat(rooms.getRoom(roomId).getInactivityDeadlineEpochMs()).isEqualTo(markReadyTime + FIFTEEN_MINUTES_MS);

        // Advance another 2 minutes and unmark ready
        long unmarkReadyTime = clock.now + 2 * 60_000L;
        clock.now = unmarkReadyTime;
        lifecycle.unmarkPlayerReadyForNextHand(roomId, "Host");
        assertThat(rooms.getRoom(roomId).getInactivityDeadlineEpochMs()).isEqualTo(unmarkReadyTime + FIFTEEN_MINUTES_MS);
    }

    @Test
    void duplicateReadinessCommandDoesNotResetDeadline() throws Exception {
        String name = uniqueName("DupReady");
        JsonNode host = createRoom(name, "Host", 4);
        joinRoom(name, "Guest");
        joinRoom(name, "Guest2");
        String roomId = host.path("roomId").asText();
        String hostToken = host.path("token").asText();

        startGame(roomId, hostToken);
        lifecycle.markPlayerReconnected(roomId, "Host");
        lifecycle.markPlayerReconnected(roomId, "Guest");
        lifecycle.markPlayerReconnected(roomId, "Guest2");
        var game = lifecycle.getGame(roomId);
        game.openReadyCountdown(clock.now + 600_000L);

        long t1 = clock.now + 1 * 60_000L;
        clock.now = t1;
        lifecycle.markPlayerReadyForNextHand(roomId, "Host");
        long deadline1 = rooms.getRoom(roomId).getInactivityDeadlineEpochMs();

        // Advance 3 minutes and call mark ready again (duplicate command)
        clock.now = clock.now + 3 * 60_000L;
        lifecycle.markPlayerReadyForNextHand(roomId, "Host");
        long deadline2 = rooms.getRoom(roomId).getInactivityDeadlineEpochMs();

        // Duplicate command does NOT reset deadline!
        assertThat(deadline2).isEqualTo(deadline1);
    }

    @Test
    void lateActivityAtOrAfterInactivityDeadlineIsRejectedAndAbandonsGame() throws Exception {
        String name = uniqueName("LateActivity");
        JsonNode host = createRoom(name, "Host", 4);
        joinRoom(name, "Guest");
        String roomId = host.path("roomId").asText();
        String hostToken = host.path("token").asText();

        startGame(roomId, hostToken);
        lifecycle.markPlayerReconnected(roomId, "Host");
        lifecycle.markPlayerReconnected(roomId, "Guest");
        lifecycle.markPlayerReconnected(roomId, "Guest2");
        var game = lifecycle.getGame(roomId);
        String actor = game.getCurrentPlayer().getName();

        long initialDeadline = rooms.getRoom(roomId).getInactivityDeadlineEpochMs();
        // Advance clock to deadline
        clock.now = initialDeadline;

        // Late action attempt must be rejected
        assertThatThrownBy(() ->
                playerActionService.processPlayerAction(roomId, new PlayerActionRequest(PlayerAction.FOLD, 0), actor))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("inactivity");

        // Game is abandoned
        assertThat(game.isGameOver()).isTrue();
        GameEndResponse review = lifecycle.getGameReview(roomId, actor);
        assertThat(review.abandoned()).isTrue();
        assertThat(review.reason()).isEqualTo("INACTIVITY");
        assertThat(review.winner()).isNull();
    }

    @Test
    void inactivityAbandonmentTakesPrecedenceOverTurnTimeout() throws Exception {
        String name = uniqueName("Precedence");
        JsonNode host = createRoom(name, "Host", 4);
        joinRoom(name, "Guest");
        String roomId = host.path("roomId").asText();
        String hostToken = host.path("token").asText();

        startGame(roomId, hostToken);
        lifecycle.markPlayerReconnected(roomId, "Host");
        lifecycle.markPlayerReconnected(roomId, "Guest");
        lifecycle.markPlayerReconnected(roomId, "Guest2");
        var game = lifecycle.getGame(roomId);

        long inactivityDeadline = rooms.getRoom(roomId).getInactivityDeadlineEpochMs();
        // Both inactivity and turn timeout are overdue
        clock.now = inactivityDeadline + 5000L;

        long turnDeadline = game.getScheduledTaskDeadline(ScheduledGameTask.TURN_TIMEOUT);
        long handNumber = game.getHandNumber();
        long generation = game.getScheduledTaskGeneration(ScheduledGameTask.TURN_TIMEOUT);

        // Turn timeout callback executes when inactivity is also due
        lifecycle.executeScheduledTask(roomId, ScheduledGameTask.TURN_TIMEOUT, turnDeadline, handNumber, generation);

        // Inactivity abandonment took precedence: no winner declared!
        assertThat(game.isGameOver()).isTrue();
        GameEndResponse review = lifecycle.getGameReview(roomId, "Host");
        assertThat(review.abandoned()).isTrue();
        assertThat(review.reason()).isEqualTo("INACTIVITY");
        assertThat(review.winner()).isNull();
    }

    @Test
    void claimWinRejectedWhenInactivityExpired() throws Exception {
        String name = uniqueName("ClaimExpired");
        JsonNode host = createRoom(name, "Host", 4);
        joinRoom(name, "Guest");
        String roomId = host.path("roomId").asText();
        String hostToken = host.path("token").asText();

        startGame(roomId, hostToken);
        lifecycle.markPlayerReconnected(roomId, "Host");
        lifecycle.markPlayerReconnected(roomId, "Guest");
        lifecycle.markPlayerReconnected(roomId, "Guest2");
        var game = lifecycle.getGame(roomId);

        // Disconnect guest so claim-win would otherwise be available
        game.findPlayerByName("Guest").setDisconnected(true);

        long inactivityDeadline = rooms.getRoom(roomId).getInactivityDeadlineEpochMs();
        clock.now = inactivityDeadline;

        // Claim win when inactivity is expired must fail and abandon game
        assertThatThrownBy(() -> lifecycle.claimWin(roomId, "Host"))
                .isInstanceOf(BadRequestException.class);

        assertThat(game.isGameOver()).isTrue();
        GameEndResponse review = lifecycle.getGameReview(roomId, "Host");
        assertThat(review.abandoned()).isTrue();
        assertThat(review.winner()).isNull();
    }

    @Test
    void abandonmentPreservesCompletedPayoutsLeavesPotUnawardedHidesCards() throws Exception {
        String name = uniqueName("AbandonmentPreservation");
        JsonNode host = createRoom(name, "Host", 4);
        joinRoom(name, "Guest");
        String roomId = host.path("roomId").asText();
        String hostToken = host.path("token").asText();

        startGame(roomId, hostToken);
        lifecycle.markPlayerReconnected(roomId, "Host");
        lifecycle.markPlayerReconnected(roomId, "Guest");
        lifecycle.markPlayerReconnected(roomId, "Guest2");
        var game = lifecycle.getGame(roomId);

        int potBefore = game.getPot();
        assertThat(potBefore).isGreaterThan(0); // Blinds posted

        // Abandon game
        lifecycle.abandonGameDueToInactivity(roomId);

        assertThat(game.isGameOver()).isTrue();
        GameEndResponse review = lifecycle.getGameReview(roomId, "Host");
        assertThat(review.abandoned()).isTrue();
        assertThat(review.reason()).isEqualTo("INACTIVITY");
        assertThat(review.winner()).isNull();

        // Pot remains unawarded in finalState
        assertThat(review.finalState().pot()).isEqualTo(potBefore);
        assertThat(review.finalState().winners()).isEmpty();

        // Opponents' private cards and hand ranks are NOT exposed
        for (var p : review.finalState().players()) {
            assertThat(p.holeCards()).isNull();
            assertThat(p.handRank()).isNull();
            assertThat(p.isWinner()).isFalse();
        }

        // Cleanup is scheduled respecting configured display delay
        Long cleanupDeadline = game.getScheduledTaskDeadline(ScheduledGameTask.CLEANUP);
        assertThat(cleanupDeadline).isEqualTo(clock.now + lifecycle.getGameEndDisplayDelayMs());
    }
}
