package com.pokergame.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How long a game waits between hands and after it ends, in milliseconds.
 *
 * @param gameEndDisplayDelayMs how long to keep the game-over result before the room and game are removed
 * @param roundEndDisplayDelayMs how long to keep a finished hand's result before the ready countdown starts,
 *                               when the game isn't over
 * @param readyCountdownMs how long players have to get ready before the next hand starts
 */
@ConfigurationProperties(prefix = "poker.game")
public record GameTimingProperties(
        @DefaultValue("20000") long gameEndDisplayDelayMs,
        @DefaultValue("0") long roundEndDisplayDelayMs,
        @DefaultValue("30000") long readyCountdownMs) {
}
