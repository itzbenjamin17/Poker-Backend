package com.pokergame;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@Tag("integration")
@DisplayName("Spring application context")
class PokerApplicationTests {

    @Test
    @DisplayName("should load the Spring Boot application context")
    void givenApplicationConfiguration_whenContextStarts_thenContextLoads() {
    }

}
