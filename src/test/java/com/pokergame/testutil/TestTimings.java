package com.pokergame.testutil;

import com.pokergame.config.GameTimingProperties;
import com.pokergame.config.RoomTimingProperties;

/**
 * Timing settings for service tests. The four constants below are the production defaults, so
 * tests keep asserting against the real values; the three-argument factory varies only the
 * display and countdown delays.
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

    public static GameTimingProperties game(long gameEndDisplayDelayMs, long roundEndDisplayDelayMs, long readyCountdownMs) {
        return new GameTimingProperties(gameEndDisplayDelayMs, roundEndDisplayDelayMs, readyCountdownMs,
                TURN_ALLOWANCE_MS, AUTO_ADVANCE_STEP_DELAY_MS, FINAL_SHOWDOWN_DISPLAY_DELAY_MS,
                GAME_INACTIVITY_ALLOWANCE_MS);
    }

    public static RoomTimingProperties room(long disconnectGracePeriodMs) {
        return new RoomTimingProperties(disconnectGracePeriodMs, LOBBY_INACTIVITY_MS);
    }
}
