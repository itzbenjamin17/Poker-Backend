package com.pokergame.config;

import com.pokergame.security.JwtService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class ConfigContractTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(JwtService.class);

    @Test
    @DisplayName("should ensure application.properties has no default JWT secret")
    void givenProductionProperties_whenChecked_thenNoSecretExists() throws Exception {
        Properties props = new Properties();
        try (InputStream is = new ClassPathResource("application.properties").getInputStream()) {
            props.load(is);
        }

        String secret = props.getProperty("app.jwt.base64-secret");
        // Allowing the environment variable placeholder but rejecting any hardcoded literal
        if (secret != null && secret.contains("${")) {
            secret = null;
        }
        
        assertThat(secret)
                .withFailMessage("Production application.properties must NOT contain a default JWT secret fallback.")
                .isNullOrEmpty();
    }

    @Test
    @DisplayName("should fail context startup when JWT secret is missing")
    void givenMissingSecret_whenContextStarts_thenThrowException() {
        contextRunner
                .run(context -> {
                    assertThat(context).hasFailed();
                    // If application.properties has ${JWT_SECRET}, it's not "missing" but "invalid base64"
                    Throwable failure = context.getStartupFailure();
                    assertThat(failure).isNotNull();
                    
                    String specificMessage = failure.getCause() != null ? failure.getCause().getMessage() : failure.getMessage();
                    assertThat(specificMessage).containsAnyOf(
                            "JWT base64 secret key is missing",
                            "JWT secret key parsing failed");
                });
    }

    @Test
    @DisplayName("should fail context startup when JWT secret is too weak")
    void givenWeakSecret_whenContextStarts_thenThrowException() {
        contextRunner
                .withPropertyValues("app.jwt.base64-secret=c2hvcnQ=") // "short" in base64
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .isInstanceOf(BeanCreationException.class)
                            .hasStackTraceContaining("Ensure it is a valid Base64 string and provides at least 512 bits");
                });
    }
}
