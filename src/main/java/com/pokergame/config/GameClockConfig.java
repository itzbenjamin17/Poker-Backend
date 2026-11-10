package com.pokergame.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Supplies the authoritative wall clock used by lifecycle deadlines. */
@Configuration
public class GameClockConfig {
    /** @return UTC clock; tests may replace it without waiting real minutes */
    @Bean
    public Clock gameClock() { return Clock.systemUTC(); }
}
