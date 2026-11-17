package com.pokergame.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Timing settings governing room membership and reconnect handling.
 *
 * @param disconnectGracePeriodMs duration a disconnected participant may reconnect before departure handling
 * @param lobbyInactivityMs how long a lobby may sit idle before it is deleted
 */
@ConfigurationProperties(prefix = "poker.room")
public record RoomTimingProperties(
        @DefaultValue("120000") long disconnectGracePeriodMs,
        @DefaultValue("1800000") long lobbyInactivityMs) {
}
