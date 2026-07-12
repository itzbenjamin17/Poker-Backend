package com.pokergame;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Bootstraps the poker backend and its Spring application context. */
@SpringBootApplication
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
