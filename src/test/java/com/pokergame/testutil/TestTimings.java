package com.pokergame.testutil;

import com.pokergame.config.GameTimingProperties;
import com.pokergame.config.RoomTimingProperties;

/**
 * Timing settings for service tests. The five allowance constants below mirror the production
 * defaults so tests keep asserting against real values, while {@link #DEFAULT_GAME} and the
 * factory methods vary display, countdown, and disconnect grace delays.
 */
public final class TestTimings {
    public static final long TURN_ALLOWANCE_MS = 60_000L;
    public static final long AUTO_ADVANCE_STEP_DELAY_MS = 4_000L;
    public static final long FINAL_SHOWDOWN_DISPLAY_DELAY_MS = 6_000L;
    public static final long GAME_INACTIVITY_ALLOWANCE_MS = 15 * 60_000L;
    public static final long LOBBY_INACTIVITY_MS = 30 * 60_000L;

    public static final GameTimingProperties DEFAULT_GAME = game(20_000L, 0L, 30_000L);

    private TestTimings() {
    }

    /**
     * Builds {@link GameTimingProperties} using the supplied display and ready-countdown delays
     * alongside the production turn, auto-advance, final-showdown, and game-inactivity defaults.
     *
     * @param gameEndDisplayDelayMs  delay before deleting a completed game
     * @param roundEndDisplayDelayMs delay before opening the ready countdown after a hand
     * @param readyCountdownMs       duration of the ready-gate countdown
     * @return configured {@link GameTimingProperties}
     */
    public static GameTimingProperties game(long gameEndDisplayDelayMs, long roundEndDisplayDelayMs, long readyCountdownMs) {
        return new GameTimingProperties(gameEndDisplayDelayMs, roundEndDisplayDelayMs, readyCountdownMs,
                TURN_ALLOWANCE_MS, AUTO_ADVANCE_STEP_DELAY_MS, FINAL_SHOWDOWN_DISPLAY_DELAY_MS,
                GAME_INACTIVITY_ALLOWANCE_MS);
    }

    /**
     * Builds {@link RoomTimingProperties} using the supplied disconnect grace period alongside the
     * production 30-minute lobby inactivity allowance.
     *
     * @param disconnectGracePeriodMs disconnect grace period in milliseconds
     * @return configured {@link RoomTimingProperties}
     */
    public static RoomTimingProperties room(long disconnectGracePeriodMs) {
        return new RoomTimingProperties(disconnectGracePeriodMs, LOBBY_INACTIVITY_MS);
    }
}
