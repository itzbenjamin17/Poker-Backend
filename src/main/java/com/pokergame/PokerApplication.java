package com.pokergame;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.pokergame.config.LifecycleTimingProperties;
import com.pokergame.config.RoomTimingProperties;

/** Bootstraps the poker backend and its Spring application context. */
@SpringBootApplication(exclude = { UserDetailsServiceAutoConfiguration.class })
@EnableConfigurationProperties({LifecycleTimingProperties.class, RoomTimingProperties.class})
public class PokerApplication {

    /**
     * Starts the application using the supplied command-line arguments.
     *
     * @param args Spring Boot command-line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(PokerApplication.class, args);
    }

}
