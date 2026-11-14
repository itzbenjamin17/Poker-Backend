package com.pokergame.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Timing settings governing game and hand lifecycle transitions.
 *
 * @param gameEndDisplayDelayMs duration to retain the terminal result before cleanup
 * @param roundEndDisplayDelayMs duration to retain a completed non-terminal hand
 * @param readyCountdownMs duration of the ready gate before a new hand starts
 */
@ConfigurationProperties(prefix = "poker.lifecycle")
public record LifecycleTimingProperties(
        @DefaultValue("20000") long gameEndDisplayDelayMs,
        @DefaultValue("0") long roundEndDisplayDelayMs,
        @DefaultValue("30000") long readyCountdownMs) {
}
