package com.pokergame.config;

import com.pokergame.persistence.config.PersistenceProperties;
import com.pokergame.security.JwtService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
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
            if (key.startsWith("logging.") || "poker.persistence.enabled".equals(key)) {
                continue;
            }
            String value = props.getProperty(key);
            if (value != null && value.startsWith("${") && value.endsWith("}")) {
                assertThat(value)
                        .withFailMessage("Property '%s' with value '%s' must NOT contain a fallback default ':' to ensure fail-fast startup.", key, value)
                        .doesNotContain(":");
            }
        }
    }

    /**
     * Protects the contract that poker.persistence.enabled has a safe fallback default of false.
     */
    @Test
    @DisplayName("should ensure poker.persistence.enabled has safe fallback default of false")
    void givenApplicationProperties_whenChecked_thenPersistenceHasSafeDefaultFalse() throws Exception {
        Properties props = new Properties();
        try (InputStream is = new ClassPathResource("application.properties").getInputStream()) {
            props.load(is);
        }

        String value = props.getProperty("poker.persistence.enabled");
        assertThat(value)
                .withFailMessage("poker.persistence.enabled must provide safe fallback default of false (${POKER_PERSISTENCE_ENABLED:false})")
                .isEqualTo("${POKER_PERSISTENCE_ENABLED:false}");
    }

    /**
     * Protects the contract that when environment variables are unset, poker.persistence.enabled defaults to false.
     */
    @Test
    @DisplayName("should default poker.persistence.enabled to false when environment variable is unset")
    void givenUnsetEnvironmentVariable_whenResolvingPersistenceProperty_thenDefaultsToFalse() {
        new ApplicationContextRunner()
                .withUserConfiguration(org.springframework.context.support.PropertySourcesPlaceholderConfigurer.class, com.pokergame.persistence.config.PersistenceProperties.class)
                .withPropertyValues("poker.persistence.enabled=${POKER_PERSISTENCE_ENABLED:false}")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    com.pokergame.persistence.config.PersistenceProperties properties = context.getBean(com.pokergame.persistence.config.PersistenceProperties.class);
                    assertThat(properties.isEnabled()).isFalse();
                    assertThat(context.getEnvironment().getProperty("poker.persistence.enabled", Boolean.class)).isFalse();
                });
    }

    /**
     * Protects the contract that application-e2e.properties explicitly disables persistence and rate limiting.
     */
    @Test
    @DisplayName("should ensure application-e2e.properties explicitly disables persistence and rate limiting")
    void givenE2eProperties_whenChecked_thenPersistenceAndRateLimitingAreDisabled() throws Exception {
        Properties props = new Properties();
        try (InputStream is = new ClassPathResource("application-e2e.properties").getInputStream()) {
            props.load(is);
        }

        assertThat(props.getProperty("poker.persistence.enabled"))
                .withFailMessage("application-e2e.properties must explicitly declare poker.persistence.enabled=false")
                .isEqualTo("false");
        assertThat(props.getProperty("poker.rate-limiting.enabled"))
                .withFailMessage("application-e2e.properties must explicitly declare poker.rate-limiting.enabled=false")
                .isEqualTo("false");
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

    /**
     * Protects the contract that when the e2e profile is active, poker.persistence.enabled evaluates to false.
     */
    @Test
    @DisplayName("should evaluate poker.persistence.enabled to false when e2e profile is active")
    void givenE2eProfile_whenActive_thenPersistenceIsEnabledFalse() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(E2eTestConfig.class)
                .web(WebApplicationType.NONE)
                .profiles("e2e")
                .run("--springdotenv.enabled=false")) {
            assertThat(context.getEnvironment().getProperty("poker.persistence.enabled", Boolean.class)).isFalse();
        }
    }

    /**
     * Protects the contract that even if an environment/dotenv source defines POKER_PERSISTENCE_ENABLED=true,
     * the e2e command line override (--poker.persistence.enabled=false) ensures persistence remains disabled.
     */
    @Test
    @DisplayName("should evaluate poker.persistence.enabled to false when e2e command line argument is provided")
    void givenE2eProfileWithCliOverride_whenActive_thenPersistenceIsEnabledFalse() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(E2eTestConfig.class)
                .web(WebApplicationType.NONE)
                .profiles("e2e")
                .run("--poker.persistence.enabled=false", "--poker.rate-limiting.enabled=false")) {
            assertThat(context.getEnvironment().getProperty("poker.persistence.enabled", Boolean.class)).isFalse();
        }
    }

    @Configuration
    static class E2eTestConfig {}
}
