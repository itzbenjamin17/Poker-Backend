package com.pokergame.security;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.security.SignatureException;
import io.jsonwebtoken.security.WeakKeyException;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
@DisplayName("JWT service")
class JwtServiceTest {

    private static final String TEST_SECRET = "test-secret-key-that-is-long-enough-for-hmac-sha-256";
    private static final long DEFAULT_EXPIRATION_MILLIS = 3_600_000L;
    private static final Duration EXPIRATION_WAIT_TIMEOUT = Duration.ofSeconds(2);

    private final JwtService jwtService = createService(TEST_SECRET, DEFAULT_EXPIRATION_MILLIS);

    @Nested
    @DisplayName("token generation")
    class TokenGeneration {

        @Test
        @DisplayName("should generate a non-empty token for a valid player name")
        void givenValidPlayerName_whenGenerateToken_thenReturnSignedToken() {
            String token = jwtService.generateToken("TestPlayer");

            assertThat(token).isNotBlank();
            assertThat(jwtService.isTokenValid(token)).isTrue();
        }

        @Test
        @DisplayName("should generate different tokens for different players")
        void givenDifferentPlayers_whenGenerateToken_thenReturnDifferentTokens() {
            String firstToken = jwtService.generateToken("Player1");
            String secondToken = jwtService.generateToken("Player2");

            assertThat(firstToken).isNotEqualTo(secondToken);
        }

        @ParameterizedTest(name = "should preserve player name \"{0}\"")
        @MethodSource("com.pokergame.security.JwtServiceTest#specialPlayerNames")
        void givenSpecialOrLongPlayerNames_whenGenerateToken_thenPlayerNameCanBeExtracted(String playerName) {
            String token = jwtService.generateToken(playerName);

            assertThat(jwtService.isTokenValid(token)).isTrue();
            assertThat(jwtService.extractPlayerName(token)).isEqualTo(playerName);
        }

        @Test
        @DisplayName("should encode an empty player name as a null subject when extracted")
        void givenEmptyPlayerName_whenGenerateToken_thenExtractPlayerNameReturnsNull() {
            String token = jwtService.generateToken("");

            assertThat(jwtService.isTokenValid(token)).isTrue();
            assertThat(jwtService.extractPlayerName(token)).isNull();
        }
    }

    @Nested
    @DisplayName("token validation")
    class TokenValidation {

        @Test
        @DisplayName("should report valid tokens as valid")
        void givenFreshToken_whenValidate_thenReturnTrue() {
            String token = jwtService.generateToken("TestPlayer");

            assertThat(jwtService.isTokenValid(token)).isTrue();
        }

        @ParameterizedTest(name = "should reject malformed token \"{0}\"")
        @NullAndEmptySource
        @ValueSource(strings = {
                "invalid.token.string",
                "not.a.jwt",
                ".",
                "..",
                "...",
                "malformed"
        })
        void givenMalformedToken_whenValidate_thenReturnFalse(String malformedToken) {
            assertThat(jwtService.isTokenValid(malformedToken)).isFalse();
        }

        @Test
        @DisplayName("should reject a tampered token")
        void givenTamperedToken_whenValidate_thenReturnFalse() {
            String token = jwtService.generateToken("TestPlayer");
            String tamperedToken = token.substring(0, token.length() - 5) + "XXXXX";

            assertThat(jwtService.isTokenValid(tamperedToken)).isFalse();
        }

        @Test
        @DisplayName("should reject a token signed with a different secret")
        void givenTokenSignedWithDifferentSecret_whenValidate_thenReturnFalse() {
            JwtService otherService = createService(
                    "different-secret-key-that-is-long-enough-for-hmac-sha-256",
                    DEFAULT_EXPIRATION_MILLIS);

            String token = otherService.generateToken("TestPlayer");

            assertThat(jwtService.isTokenValid(token)).isFalse();
        }

        @Test
        @DisplayName("should report an expired token as invalid without using hard coded sleeps")
        void givenExpiredToken_whenValidate_thenReturnFalse() {
            JwtService shortLivedService = createService(TEST_SECRET, 25L);
            String token = shortLivedService.generateToken("TestPlayer");

            Awaitility.await()
                    .atMost(EXPIRATION_WAIT_TIMEOUT)
                    .until(() -> !shortLivedService.isTokenValid(token));

            assertThat(shortLivedService.isTokenValid(token)).isFalse();
        }
    }

    @Nested
    @DisplayName("player extraction")
    class PlayerExtraction {

        @ParameterizedTest(name = "should extract player name \"{0}\"")
        @ValueSource(strings = { "Player1", "Player2", "Alice", "Bob", "Admin" })
        void givenValidToken_whenExtractPlayerName_thenReturnSubject(String playerName) {
            String token = jwtService.generateToken(playerName);

            assertThat(jwtService.extractPlayerName(token)).isEqualTo(playerName);
        }

        @Test
        @DisplayName("should throw when extracting from an invalid token")
        void givenInvalidToken_whenExtractPlayerName_thenThrow() {
            assertThatThrownBy(() -> jwtService.extractPlayerName("invalid.token.string"))
                    .isInstanceOf(Exception.class);
        }

        @Test
        @DisplayName("should throw ExpiredJwtException when the token has expired")
        void givenExpiredToken_whenExtractPlayerName_thenThrowExpiredJwtException() {
            JwtService shortLivedService = createService(TEST_SECRET, 25L);
            String token = shortLivedService.generateToken("TestPlayer");

            Awaitility.await()
                    .atMost(EXPIRATION_WAIT_TIMEOUT)
                    .ignoreExceptions()
                    .untilAsserted(() -> assertThatThrownBy(() -> shortLivedService.extractPlayerName(token))
                            .isInstanceOf(ExpiredJwtException.class));
        }

        @Test
        @DisplayName("should throw SignatureException when the token has been tampered with")
        void givenTamperedToken_whenExtractPlayerName_thenThrowSignatureException() {
            String token = jwtService.generateToken("TestPlayer");
            String tamperedToken = token.substring(0, token.length() - 5) + "XXXXX";

            assertThatThrownBy(() -> jwtService.extractPlayerName(tamperedToken))
                    .isInstanceOf(SignatureException.class);
        }
    }

    @Nested
    @DisplayName("service initialization")
    class Initialization {

        @ParameterizedTest(name = "should accept secret \"{0}\"")
        @ValueSource(strings = {
                "this-is-a-long-enough-secret-key-for-hmac-sha-256-algorithm",
                "another-valid-secret-with-special-chars-!@#$%^&*()_+-=[]{}",
                "very-long-secret-key-that-is-definitely-long-enough-12345678",
                "12345678901234567890123456789012345678901234567890123456"
        })
        void givenValidSecret_whenInitialise_thenDoNotThrow(String secret) {
            JwtService service = new JwtService();
            ReflectionTestUtils.setField(service, "secretKeyString", secret);
            ReflectionTestUtils.setField(service, "expirationMillis", DEFAULT_EXPIRATION_MILLIS);

            org.junit.jupiter.api.Assertions.assertDoesNotThrow(service::init);
        }

        @Test
        @DisplayName("should reject a weak secret key")
        void givenShortSecret_whenInitialise_thenThrowWeakKeyException() {
            JwtService service = new JwtService();
            ReflectionTestUtils.setField(service, "secretKeyString", "short");
            ReflectionTestUtils.setField(service, "expirationMillis", DEFAULT_EXPIRATION_MILLIS);

            assertThatThrownBy(service::init).isInstanceOf(WeakKeyException.class);
        }
    }

    @Test
    @DisplayName("should keep the generate validate extract lifecycle consistent")
    void givenGeneratedToken_whenRunningLifecycle_thenValidateAndExtractSuccessfully() {
        String token = jwtService.generateToken("TestPlayer");

        assertThat(jwtService.isTokenValid(token)).isTrue();
        assertThat(jwtService.extractPlayerName(token)).isEqualTo("TestPlayer");
        assertThat(token.split("\\.")).hasSize(3).allSatisfy(part -> assertThat(part).isNotBlank());
    }

    private JwtService createService(String secret, long expirationMillis) {
        JwtService service = new JwtService();
        ReflectionTestUtils.setField(service, "secretKeyString", secret);
        ReflectionTestUtils.setField(service, "expirationMillis", expirationMillis);
        service.init();
        return service;
    }

    private static Stream<String> specialPlayerNames() {
        return Stream.of("Player@123!#$%", "玩家123", "A".repeat(1000));
    }
}
