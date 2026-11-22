package com.pokergame.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.pokergame.persistence.config.PersistenceProperties;
import com.pokergame.security.JwtService;
import com.pokergame.testutil.TestTimings;
import io.jsonwebtoken.io.DecodingException;
import io.jsonwebtoken.security.WeakKeyException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * Integration tests for the startup configuration contract: required secrets, fail-fast placeholders,
 * safe defaults, the {@code test} and {@code frontend-e2e} profile values, and test timing constants
 * that must keep matching the production defaults.
 */
@Tag("integration")
@DisplayName("Configuration contract")
class ConfigContractIT {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");
    private static final String VALID_SECRET =
            "Y2VudHVyeS1vbGQtc2VjcmV0LWtleS10aGF0LWlzLWRlZmluaXRlbHktbG9uZy1lbm91Z2gtZm9yLWhtYWMtc2hhLTUxMg==";
    private static final String MISSING_SECRET_MESSAGE =
            "JWT base64 secret key is missing. Please provide it via 'app.jwt.base64-secret' "
                    + "or the JWT_SECRET environment variable.";
    private static final String UNUSABLE_SECRET_MESSAGE =
            "JWT secret key parsing failed. Ensure it is a valid Base64 string and provides at least 512 bits "
                    + "of key material.";

    @Test
    @DisplayName("should obtain the JWT signing secret exclusively from JWT_SECRET")
    void productionSigningSecretRequiresEnvironmentVariable() throws IOException {
        assertThat(loadApplicationProperties().getProperty("app.jwt.base64-secret")).isEqualTo("${JWT_SECRET}");
    }

    @Test
    @DisplayName("should accept a valid 512-bit Base64 JWT secret")
    void givenValidSecret_whenContextStarts_thenJwtServiceIsCreated() {
        jwtServiceRunner()
                .withPropertyValues("app.jwt.base64-secret=" + VALID_SECRET)
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(JwtService.class));
    }

    @ParameterizedTest(name = "[{index}] secret=\"{0}\"")
    @ValueSource(strings = {"", "   "})
    @DisplayName("should fail context startup with the missing-secret message when the JWT secret is blank")
    void givenBlankSecret_whenContextStarts_thenReportsMissingSecret(String blankSecret) {
        jwtServiceRunner()
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new MapPropertySource("blankSecret", Map.of("app.jwt.base64-secret", blankSecret))))
                .run(context -> {
                    assertThat(context).hasFailed();
                    IllegalStateException failure = causeOfType(context.getStartupFailure(), IllegalStateException.class);
                    assertThat(failure).hasMessage(MISSING_SECRET_MESSAGE).hasNoCause();
                });
    }

    @Test
    @DisplayName("should fail context startup with the parsing-failed message when the JWT secret is not Base64")
    void givenInvalidBase64Secret_whenContextStarts_thenReportsParsingFailure() {
        jwtServiceRunner()
                .withPropertyValues("app.jwt.base64-secret=not*valid*base64!")
                .run(context -> {
                    assertThat(context).hasFailed();
                    IllegalStateException failure = causeOfType(context.getStartupFailure(), IllegalStateException.class);
                    assertThat(failure).hasMessage(UNUSABLE_SECRET_MESSAGE).hasCauseInstanceOf(DecodingException.class);
                });
    }

    @Test
    @DisplayName("should fail context startup when JWT secret is too weak")
    void givenWeakSecret_whenContextStarts_thenThrowException() {
        jwtServiceRunner()
                .withPropertyValues("app.jwt.base64-secret=c2hvcnQ=") // "short" in base64
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(BeanCreationException.class);
                    IllegalStateException failure = causeOfType(context.getStartupFailure(), IllegalStateException.class);
                    assertThat(failure).hasMessage(UNUSABLE_SECRET_MESSAGE).hasCauseInstanceOf(WeakKeyException.class);
                });
    }

    @Test
    @DisplayName("should ensure application.properties placeholders have no fallback defaults")
    void givenApplicationProperties_whenChecked_thenAllPlaceholdersHaveNoDefaults() throws IOException {
        Properties props = loadApplicationProperties();
        Set<String> placeholderNames = new TreeSet<>();

        for (String key : props.stringPropertyNames()) {
            Matcher placeholders = PLACEHOLDER.matcher(props.getProperty(key));
            while (placeholders.find()) {
                String placeholder = placeholders.group(1);
                placeholderNames.add(placeholder);
                if (key.startsWith("logging.") || "poker.persistence.enabled".equals(key)) {
                    continue;
                }
                assertThat(placeholder)
                        .withFailMessage("Property '%s' uses placeholder '${%s}' with a fallback default ':'; "
                                + "required settings must fail fast at startup.", key, placeholder)
                        .doesNotContain(":");
            }
        }

        // Guards against the scan silently matching nothing.
        assertThat(placeholderNames).contains("JWT_SECRET", "SERVER_PORT", "CORS_ALLOWED_ORIGINS");
    }

    @Test
    @DisplayName("should ensure poker.persistence.enabled has safe fallback default of false")
    void givenApplicationProperties_whenChecked_thenPersistenceHasSafeDefaultFalse() throws IOException {
        String value = loadApplicationProperties().getProperty("poker.persistence.enabled");

        assertThat(value)
                .withFailMessage("poker.persistence.enabled must provide safe fallback default of false (${POKER_PERSISTENCE_ENABLED:false})")
                .isEqualTo("${POKER_PERSISTENCE_ENABLED:false}");
    }

    @Test
    @DisplayName("should default poker.persistence.enabled to false when environment variable is unset")
    void givenUnsetEnvironmentVariable_whenResolvingPersistenceProperty_thenDefaultsToFalse() {
        isolatedRunner()
                .withUserConfiguration(PropertySourcesPlaceholderConfigurer.class, PersistencePropertiesConfig.class)
                .withPropertyValues("poker.persistence.enabled=${POKER_PERSISTENCE_ENABLED:false}")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    PersistenceProperties properties = context.getBean(PersistenceProperties.class);
                    assertThat(properties.enabled()).isFalse();
                    assertThat(context.getEnvironment().getProperty("poker.persistence.enabled", Boolean.class)).isFalse();
                });
    }

    @Test
    @DisplayName("should honor POKER_PERSISTENCE_ENABLED when the environment variable is set")
    void givenEnvironmentVariableSet_whenResolvingPersistenceProperty_thenUsesIt() {
        isolatedRunner()
                .withUserConfiguration(PropertySourcesPlaceholderConfigurer.class, PersistencePropertiesConfig.class)
                .withPropertyValues(
                        "poker.persistence.enabled=${POKER_PERSISTENCE_ENABLED:false}",
                        "POKER_PERSISTENCE_ENABLED=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(PersistenceProperties.class).enabled()).isTrue();
                });
    }

    @Test
    @DisplayName("should fail fast when required placeholder is missing")
    void givenMissingPlaceholder_whenContextStarts_thenFailFast() {
        jwtServiceRunner()
                .withUserConfiguration(PropertySourcesPlaceholderConfigurer.class)
                .withPropertyValues("app.jwt.base64-secret=${UNRESOLVED_MANDATORY_ENV_VAR}")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .isNotNull()
                            .hasRootCauseInstanceOf(IllegalArgumentException.class)
                            .hasStackTraceContaining("Could not resolve placeholder 'UNRESOLVED_MANDATORY_ENV_VAR'");
                });
    }

    @Test
    @DisplayName("should keep TestTimings in step with the production timing defaults")
    void givenNoTimingProperties_whenBound_thenTestTimingsMatchProductionDefaults() {
        isolatedRunner()
                .withUserConfiguration(TimingPropertiesConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    GameTimingProperties gameDefaults = context.getBean(GameTimingProperties.class);
                    RoomTimingProperties roomDefaults = context.getBean(RoomTimingProperties.class);

                    assertThat(TestTimings.DEFAULT_GAME).isEqualTo(gameDefaults);
                    assertThat(TestTimings.room(roomDefaults.disconnectGracePeriodMs())).isEqualTo(roomDefaults);
                });
    }

    @Test
    @DisplayName("should layer the test profile values under the frontend-e2e profile")
    void givenTestAndFrontendE2eProfiles_whenResolved_thenFrontendValuesWin() {
        profileRunner("test", "frontend-e2e").run(context -> {
            assertThat(context).hasNotFailed();
            var environment = context.getEnvironment();
            assertThat(environment.getProperty("server.port", Integer.class)).isEqualTo(8080);
            assertThat(environment.getProperty("poker.game.round-end-display-delay-ms", Long.class)).isZero();
            assertThat(environment.getProperty("poker.game.ready-countdown-ms", Long.class)).isEqualTo(900L);
            assertThat(environment.getProperty("poker.game.game-end-display-delay-ms", Long.class)).isEqualTo(500L);
            assertThat(environment.getProperty("poker.rate-limiting.enabled", Boolean.class)).isFalse();
            assertThat(environment.getProperty("poker.persistence.enabled", Boolean.class)).isFalse();
            assertThat(environment.getProperty("app.security.cors.allowed-origins"))
                    .isEqualTo("http://localhost:5173,http://localhost");
            // Values only the test profile provides stay in force beneath the frontend profile.
            assertThat(environment.getProperty("app.jwt.base64-secret")).isEqualTo(VALID_SECRET);
            assertThat(environment.getProperty("poker.room.disconnect-grace-period-ms", Long.class)).isEqualTo(120_000L);
        });
    }

    @Test
    @DisplayName("should use the shortened ready and game-end timings only under the frontend-e2e profile")
    void givenTestProfileOnly_whenResolved_thenFrontendTimingsAreNotApplied() {
        profileRunner("test").run(context -> {
            assertThat(context).hasNotFailed();
            var environment = context.getEnvironment();
            assertThat(environment.getProperty("server.port", Integer.class)).isZero();
            assertThat(environment.getProperty("poker.game.ready-countdown-ms", Long.class)).isEqualTo(60_000L);
            assertThat(environment.getProperty("poker.game.game-end-display-delay-ms", Long.class)).isEqualTo(60_000L);
            assertThat(environment.getProperty("poker.rate-limiting.enabled", Boolean.class)).isFalse();
            assertThat(environment.getProperty("poker.persistence.enabled", Boolean.class)).isFalse();
        });
    }

    @Test
    @DisplayName("should pin the frontend-e2e toggles in application-frontend-e2e.properties itself")
    void givenFrontendE2eProperties_whenLoaded_thenTogglesAreExplicitlyPinned() throws IOException {
        Properties props = loadProperties("application-frontend-e2e.properties");

        assertThat(props.getProperty("poker.rate-limiting.enabled")).isEqualTo("false");
        assertThat(props.getProperty("poker.persistence.enabled")).isEqualTo("false");
        assertThat(props.getProperty("app.security.cors.allowed-origins")).isEqualTo("http://localhost:5173,http://localhost");
        assertThat(props.getProperty("poker.game.round-end-display-delay-ms")).isEqualTo("0");
        assertThat(props.getProperty("poker.game.ready-countdown-ms")).isEqualTo("900");
        assertThat(props.getProperty("poker.game.game-end-display-delay-ms")).isEqualTo("500");
    }

    // Runner whose environment ignores the machine's real system properties and environment variables.
    private static ApplicationContextRunner isolatedRunner() {
        return new ApplicationContextRunner().withInitializer(context -> {
            var propertySources = context.getEnvironment().getPropertySources();
            propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            propertySources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        });
    }

    private static ApplicationContextRunner jwtServiceRunner() {
        return isolatedRunner().withUserConfiguration(GameClockConfig.class, JwtService.class);
    }

    // Loads application.properties plus the named profile files, as Spring Boot does at startup.
    private static ApplicationContextRunner profileRunner(String... profiles) {
        return isolatedRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=" + String.join(",", profiles));
    }

    private static Properties loadApplicationProperties() throws IOException {
        return loadProperties("application.properties");
    }

    private static Properties loadProperties(String classpathResource) throws IOException {
        Properties props = new Properties();
        try (InputStream is = new ClassPathResource(classpathResource).getInputStream()) {
            props.load(is);
        }
        return props;
    }

    private static <T extends Throwable> T causeOfType(Throwable failure, Class<T> type) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return type.cast(cause);
            }
        }
        throw new AssertionError("No " + type.getSimpleName() + " in the cause chain of " + failure);
    }

    @Configuration
    @EnableConfigurationProperties(PersistenceProperties.class)
    static class PersistencePropertiesConfig { }

    @Configuration
    @EnableConfigurationProperties({GameTimingProperties.class, RoomTimingProperties.class})
    static class TimingPropertiesConfig { }
}
