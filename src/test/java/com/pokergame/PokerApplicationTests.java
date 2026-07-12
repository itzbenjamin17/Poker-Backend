package com.pokergame;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Groups test scenarios for poker application tests. */
@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
@DisplayName("Spring application context")
class PokerApplicationTests {

    /**
     * Protects the contract that the system should load the Spring Boot application context.
     */
    @Test
    @DisplayName("should load the Spring Boot application context")
    void givenApplicationConfiguration_whenContextStarts_thenContextLoads() {
    }

}
