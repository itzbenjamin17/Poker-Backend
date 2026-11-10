package com.pokergame.config;

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
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import com.pokergame.persistence.config.PersistenceProperties;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;

/** Tests config contract behavior. */
@Tag("unit")
class ConfigContractTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(JwtService.class);

    @Test
    @DisplayName("should obtain the JWT signing secret exclusively from JWT_SECRET")
    void productionSigningSecretRequiresEnvironmentVariable() throws Exception {
        Properties props = new Properties();
        try (InputStream is = new ClassPathResource("application.properties").getInputStream()) {
            props.load(is);
        }

        assertThat(props.getProperty("app.jwt.base64-secret")).isEqualTo("${JWT_SECRET}");
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
                .withUserConfiguration(PropertySourcesPlaceholderConfigurer.class, PersistenceProperties.class)
                .withPropertyValues("poker.persistence.enabled=${POKER_PERSISTENCE_ENABLED:false}")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    PersistenceProperties properties = context.getBean(PersistenceProperties.class);
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
                .withUserConfiguration(PropertySourcesPlaceholderConfigurer.class, JwtService.class)
                .withPropertyValues("app.jwt.base64-secret=${UNRESOLVED_MANDATORY_ENV_VAR}")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .isNotNull()
                            .hasRootCauseInstanceOf(IllegalArgumentException.class)
                            .hasStackTraceContaining("Could not resolve placeholder 'UNRESOLVED_MANDATORY_ENV_VAR'");
                });
    }

    /** Covers the profile's config and CLI precedence over an environment requesting persistence. */
    @Test
    void persistenceIsDisabledByE2eProfileAndExplicitOverride() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(E2eTestConfig.class)
                .web(WebApplicationType.NONE)
                .profiles("e2e")
                .run("--springdotenv.enabled=false")) {
            assertThat(context.getEnvironment().getProperty("poker.persistence.enabled", Boolean.class)).isFalse();
        }

        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(E2eTestConfig.class)
                .web(WebApplicationType.NONE)
                .initializers(ctx -> ctx.getEnvironment().getPropertySources().addAfter(
                        StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
                        new SystemEnvironmentPropertySource("persistence-test-environment",
                                Map.of("POKER_PERSISTENCE_ENABLED", "true"))))
                .run("--springdotenv.enabled=false", "--poker.persistence.enabled=false")) {
            assertThat(context.getEnvironment().getProperty("POKER_PERSISTENCE_ENABLED")).isEqualTo("true");
            assertThat(context.getEnvironment().getProperty("poker.persistence.enabled", Boolean.class)).isFalse();
        }
    }

    @Configuration
    static class E2eTestConfig {}
}
