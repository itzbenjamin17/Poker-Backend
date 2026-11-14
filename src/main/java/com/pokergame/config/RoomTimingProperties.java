package com.pokergame.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Timing settings governing room membership and reconnect handling.
 *
 * @param disconnectGracePeriodMs duration a disconnected participant may reconnect before departure handling
 */
@ConfigurationProperties(prefix = "poker.room")
public record RoomTimingProperties(@DefaultValue("120000") long disconnectGracePeriodMs) {
}
