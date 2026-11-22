package com.pokergame.controller;

import com.pokergame.testutil.MutableClock;
import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Gives the controller slice a {@link MutableClock} that wins over the system clock, so rate-limit
 * windows can be crossed by moving the clock instead of waiting.
 */
@TestConfiguration(proxyBeanMethods = false)
class ControllerSliceClockConfig {

    /** Reading the clock is reset to before every test. */
    static final long START_EPOCH_MS = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();

    @Bean
    @Primary
    MutableClock controllerSliceClock() {
        return new MutableClock(START_EPOCH_MS);
    }
}
