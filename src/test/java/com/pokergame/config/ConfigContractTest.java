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

/** Tests config contract behavior. */
@Tag("unit")
class ConfigContractTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(JwtService.class);

    /**
     * Protects the contract that the system should ensure application.properties has no default JWT secret.
     */
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

    /**
     * Protects the contract that the system should fail context startup when JWT secret is missing.
     */
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

    /**
     * Protects the contract that the system should fail context startup when JWT secret is too weak.
     */
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

    /**
     * Protects the contract that all placeholders in application.properties have no fallback defaults, enforcing fail-fast.
     */
    @Test
    @DisplayName("should ensure application.properties placeholders have no fallback defaults")
    void givenApplicationProperties_whenChecked_thenAllPlaceholdersHaveNoDefaults() throws Exception {
        Properties props = new Properties();
        try (InputStream is = new ClassPathResource("application.properties").getInputStream()) {
            props.load(is);
        }

        for (String key : props.stringPropertyNames()) {
            String value = props.getProperty(key);
            if (value != null && value.startsWith("${") && value.endsWith("}")) {
                assertThat(value)
                        .withFailMessage("Property '%s' with value '%s' must NOT contain a fallback default ':' to ensure fail-fast startup.", key, value)
                        .doesNotContain(":");
            }
        }
    }

    /**
     * Protects the contract that missing placeholders fail fast during bean resolution.
     */
    @Test
    @DisplayName("should fail fast when required placeholder is missing")
    void givenMissingPlaceholder_whenContextStarts_thenFailFast() {
        new ApplicationContextRunner()
                .withUserConfiguration(org.springframework.context.support.PropertySourcesPlaceholderConfigurer.class, JwtService.class)
                .withPropertyValues("app.jwt.base64-secret=${UNRESOLVED_MANDATORY_ENV_VAR}")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .isNotNull()
                            .hasRootCauseInstanceOf(IllegalArgumentException.class)
                            .hasStackTraceContaining("Could not resolve placeholder 'UNRESOLVED_MANDATORY_ENV_VAR'");
                });
    }
}
