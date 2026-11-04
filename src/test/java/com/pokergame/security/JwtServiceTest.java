package com.pokergame.security;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.security.SignatureException;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.stream.Stream;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Assertions;

/** Tests JWT service behavior. */
@Tag("unit")
@DisplayName("JWT service")
class JwtServiceTest {

    private static final String TEST_SECRET = "Y2VudHVyeS1vbGQtc2VjcmV0LWtleS10aGF0LWlzLWRlZmluaXRlbHktbG9uZy1lbm91Z2gtZm9yLWhtYWMtc2hhLTUxMg==";
    private static final long DEFAULT_EXPIRATION_MILLIS = 3_600_000L;
    private static final Duration EXPIRATION_WAIT_TIMEOUT = Duration.ofSeconds(2);

    private final JwtService jwtService = createService(TEST_SECRET, DEFAULT_EXPIRATION_MILLIS);
    private static final String TEST_ROOM = "test-room";

    /** Groups test scenarios for token generation. */
    @Nested
    @DisplayName("token generation")
    class TokenGeneration {

        /**
         * Protects the contract that the system should generate a non-empty token for a valid player name.
         */
        @Test
        @DisplayName("should generate a non-empty token for a valid player name")
        void givenValidPlayerName_whenGenerateToken_thenReturnSignedToken() {
            String token = jwtService.generateToken("TestPlayer", TEST_ROOM);

            assertThat(token).isNotBlank();
            assertThat(jwtService.isTokenValid(token)).isTrue();
        }

        /**
         * Protects the contract that the system should generate different tokens for different players.
         */
        @Test
        @DisplayName("should generate different tokens for different players")
        void givenDifferentPlayers_whenGenerateToken_thenReturnDifferentTokens() {
            String firstToken = jwtService.generateToken("Player1", TEST_ROOM);
            String secondToken = jwtService.generateToken("Player2", TEST_ROOM);

            assertThat(firstToken).isNotEqualTo(secondToken);
        }

        /**
         * Protects the scenario: given special or long player names, when generate token, then player name can be extracted.
         * @param playerName player name supplied to the fixture
         */
        @ParameterizedTest(name = "should preserve player name \"{0}\"")
        @MethodSource("com.pokergame.security.JwtServiceTest#specialPlayerNames")
        void givenSpecialOrLongPlayerNames_whenGenerateToken_thenPlayerNameCanBeExtracted(String playerName) {
            String token = jwtService.generateToken(playerName, TEST_ROOM);

            assertThat(jwtService.isTokenValid(token)).isTrue();
            assertThat(jwtService.extractPlayerName(token)).isEqualTo(playerName);
        }

        /**
         * Protects the contract that the system should reject an empty player name subject.
         */
        @Test
        @DisplayName("should reject an empty player name subject")
        void givenEmptyPlayerName_whenGenerateToken_thenIsTokenValidReturnsFalse() {
            String token = jwtService.generateToken("", TEST_ROOM);

            assertThat(jwtService.isTokenValid(token)).isFalse();
        }

        /**
         * Protects the contract that the system should reject a token with missing or blank roomId claim.
         */
        @Test
        @DisplayName("should reject a token with missing or blank roomId claim")
        void givenMissingRoomId_whenValidate_thenReturnFalse() {
            // Manually build a token with missing roomId using Jwts
            String token = io.jsonwebtoken.Jwts.builder()
                    .subject("TestPlayer")
                    .signWith((SecretKey) ReflectionTestUtils.getField(jwtService, "secretKey"))
                    .compact();

            assertThat(jwtService.isTokenValid(token)).isFalse();
            assertThatThrownBy(() -> jwtService.extractPrincipal(token))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        /**
         * Protects the contract that the system should reject a token with blank roomId claim.
         */
        @Test
        @DisplayName("should reject a token with blank roomId claim")
        void givenBlankRoomId_whenValidate_thenReturnFalse() {
            String token = io.jsonwebtoken.Jwts.builder()
                    .subject("TestPlayer")
                    .claim("roomId", "   ")
                    .signWith((SecretKey) ReflectionTestUtils.getField(jwtService, "secretKey"))
                    .compact();

            assertThat(jwtService.isTokenValid(token)).isFalse();
            assertThatThrownBy(() -> jwtService.extractPrincipal(token))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        /**
         * Protects the contract that token generation with membership ID includes the claim in the JWT.
         */
        @Test
        @DisplayName("should include membershipId claim when provided in token generation")
        void givenMembershipId_whenGenerateToken_thenTokenIncludesMembershipIdClaim() {
            String membershipId = "mem-session-12345";
            String token = jwtService.generateToken("TestPlayer", TEST_ROOM, membershipId);

            assertThat(token).isNotBlank();
            assertThat(jwtService.isTokenValid(token)).isTrue();

            var claims = io.jsonwebtoken.Jwts.parser()
                    .verifyWith((SecretKey) ReflectionTestUtils.getField(jwtService, "secretKey"))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            assertThat(claims.get("membershipId", String.class)).isEqualTo(membershipId);
        }

        /**
         * Protects the contract that two tokens for the same (playerName, roomId) with different membership IDs produce distinct tokens.
         */
        @Test
        @DisplayName("should produce distinct tokens for different membership IDs")
        void givenDifferentMembershipIds_whenGenerateToken_thenReturnDifferentTokens() {
            String firstToken = jwtService.generateToken("Player1", TEST_ROOM, "membership-A");
            String secondToken = jwtService.generateToken("Player1", TEST_ROOM, "membership-B");

            assertThat(firstToken).isNotEqualTo(secondToken);
        }

        /**
         * Protects the contract that blank membership ID does not embed a membershipId claim.
         */
        @Test
        @DisplayName("should omit membership claim when membership ID is blank or null")
        void givenBlankMembershipId_whenGenerateToken_thenOmitMembershipClaim() {
            String token = jwtService.generateToken("Player1", TEST_ROOM, "   ");

            var claims = io.jsonwebtoken.Jwts.parser()
                    .verifyWith((SecretKey) ReflectionTestUtils.getField(jwtService, "secretKey"))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            assertThat(claims.get("membershipId", String.class)).isNull();
        }
    }

    /** Groups test scenarios for token validation. */
    @Nested
    @DisplayName("token validation")
    class TokenValidation {

        /**
         * Protects the contract that the system should report valid tokens as valid.
         */
        @Test
        @DisplayName("should report valid tokens as valid")
        void givenFreshToken_whenValidate_thenReturnTrue() {
            String token = jwtService.generateToken("TestPlayer", TEST_ROOM);

            assertThat(jwtService.isTokenValid(token)).isTrue();
        }

        /**
         * Protects the scenario: given malformed token, when validate, then return false.
         * @param malformedToken malformed token supplied to the fixture
         */
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

        /**
         * Protects the contract that the system should reject a tampered token.
         */
        @Test
        @DisplayName("should reject a tampered token")
        void givenTamperedToken_whenValidate_thenReturnFalse() {
            String token = jwtService.generateToken("TestPlayer", TEST_ROOM);
            String tamperedToken = token.substring(0, token.length() - 5) + "XXXXX";

            assertThat(jwtService.isTokenValid(tamperedToken)).isFalse();
        }

        /**
         * Protects the contract that the system should reject a token signed with a different secret.
         */
        @Test
        @DisplayName("should reject a token signed with a different secret")
        void givenTokenSignedWithDifferentSecret_whenValidate_thenReturnFalse() {
            JwtService otherService = createService(
                    "YW5vdGhlci1kaWZmZXJlbnQtc2VjcmV0LWtleS10aGF0LWlzLWRlZmluaXRlbHktbG9uZy1lbm91Z2gtZm9yLWhtYWMtc2hhLTUxMg==",
                    DEFAULT_EXPIRATION_MILLIS);

            String token = otherService.generateToken("TestPlayer", TEST_ROOM);

            assertThat(jwtService.isTokenValid(token)).isFalse();
        }

        /**
         * Protects the contract that the system should report an expired token as invalid without using hard coded sleeps.
         */
        @Test
        @DisplayName("should report an expired token as invalid without using hard coded sleeps")
        void givenExpiredToken_whenValidate_thenReturnFalse() {
            JwtService shortLivedService = createService(TEST_SECRET, 25L);
            String token = shortLivedService.generateToken("TestPlayer", TEST_ROOM);

            Awaitility.await()
                    .atMost(EXPIRATION_WAIT_TIMEOUT)
                    .until(() -> !shortLivedService.isTokenValid(token));

            assertThat(shortLivedService.isTokenValid(token)).isFalse();
        }
    }

    /** Groups test scenarios for player extraction. */
    @Nested
    @DisplayName("player extraction")
    class PlayerExtraction {

        /**
         * Protects the scenario: given valid token, when extract player name, then return subject.
         * @param playerName player name supplied to the fixture
         */
        @ParameterizedTest(name = "should extract player name \"{0}\"")
        @ValueSource(strings = { "Player1", "Player2", "Alice", "Bob", "Admin" })
        void givenValidToken_whenExtractPlayerName_thenReturnSubject(String playerName) {
            String token = jwtService.generateToken(playerName, TEST_ROOM);

            assertThat(jwtService.extractPlayerName(token)).isEqualTo(playerName);
        }

        /**
         * Protects the contract that the system should extract full principal including room ID.
         */
        @Test
        @DisplayName("should extract full principal including room ID")
        void givenValidToken_whenExtractPrincipal_thenReturnPlayerPrincipal() {
            String playerName = "Alice";
            String token = jwtService.generateToken(playerName, TEST_ROOM);

            PlayerPrincipal principal = jwtService.extractPrincipal(token);

            assertThat(principal.playerName()).isEqualTo(playerName);
            assertThat(principal.roomId()).isEqualTo(TEST_ROOM);
            assertThat(principal.getName()).isEqualTo(playerName + ":" + TEST_ROOM + ":" + principal.membershipId());
        }

        /**
         * Protects the contract that the system should throw when extracting from an invalid token.
         */
        @Test
        @DisplayName("should throw when extracting from an invalid token")
        void givenInvalidToken_whenExtractPlayerName_thenThrow() {
            assertThatThrownBy(() -> jwtService.extractPlayerName("invalid.token.string"))
                    .isInstanceOf(Exception.class);
        }

        /**
         * Protects the contract that the system should throw ExpiredJwtException when the token has expired.
         */
        @Test
        @DisplayName("should throw ExpiredJwtException when the token has expired")
        void givenExpiredToken_whenExtractPlayerName_thenThrowExpiredJwtException() {
            JwtService shortLivedService = createService(TEST_SECRET, 25L);
            String token = shortLivedService.generateToken("TestPlayer", TEST_ROOM);

            Awaitility.await()
                    .atMost(EXPIRATION_WAIT_TIMEOUT)
                    .ignoreExceptions()
                    .untilAsserted(() -> assertThatThrownBy(() -> shortLivedService.extractPlayerName(token))
                            .isInstanceOf(ExpiredJwtException.class));
        }

        /**
         * Protects the contract that the system should throw SignatureException when the token has been tampered with.
         */
        @Test
        @DisplayName("should throw SignatureException when the token has been tampered with")
        void givenTamperedToken_whenExtractPlayerName_thenThrowSignatureException() {
            String token = jwtService.generateToken("TestPlayer", TEST_ROOM);
            String tamperedToken = token.substring(0, token.length() - 5) + "XXXXX";

            assertThatThrownBy(() -> jwtService.extractPlayerName(tamperedToken))
                    .isInstanceOf(SignatureException.class);
        }

        /**
         * Protects the contract that extracting principal from a token with membership ID recovers the exact membership ID.
         */
        @Test
        @DisplayName("should extract principal recovering exact membership ID")
        void givenTokenWithMembershipId_whenExtractPrincipal_thenReturnPrincipalWithMembershipId() {
            String membershipId = "session-nonce-789";
            String token = jwtService.generateToken("Alice", TEST_ROOM, membershipId);

            PlayerPrincipal principal = jwtService.extractPrincipal(token);

            assertThat(principal.playerName()).isEqualTo("Alice");
            assertThat(principal.roomId()).isEqualTo(TEST_ROOM);
            assertThat(principal.membershipId()).isEqualTo(membershipId);
            assertThat(principal.getMembershipId()).isEqualTo(membershipId);
            assertThat(principal.getPlayerName()).isEqualTo("Alice");
            assertThat(principal.getRoomId()).isEqualTo(TEST_ROOM);
            assertThat(principal.getName()).isEqualTo("Alice:" + TEST_ROOM + ":" + membershipId);
        }

        /**
         * Protects the contract that two tokens with different membership IDs produce distinct principals.
         */
        @Test
        @DisplayName("should produce distinct principals for distinct membership IDs")
        void givenDifferentMembershipIds_whenExtractPrincipal_thenReturnDistinctPrincipals() {
            String tokenA = jwtService.generateToken("Alice", TEST_ROOM, "session-1");
            String tokenB = jwtService.generateToken("Alice", TEST_ROOM, "session-2");

            PlayerPrincipal principalA = jwtService.extractPrincipal(tokenA);
            PlayerPrincipal principalB = jwtService.extractPrincipal(tokenB);

            assertThat(principalA).isNotEqualTo(principalB);
            assertThat(principalA.hashCode()).isNotEqualTo(principalB.hashCode());
            assertThat(principalA.membershipId()).isEqualTo("session-1");
            assertThat(principalB.membershipId()).isEqualTo("session-2");
        }

        /**
         * Rejects legacy signed tokens without a membership claim.
         */
        @Test
        @DisplayName("should reject principal extraction when token lacks membership claim")
        void givenTokenWithoutMembershipId_whenExtractPrincipal_thenReject() {
            String token = jwtService.generateToken("Alice", TEST_ROOM, null);
            assertThat(jwtService.isTokenValid(token)).isFalse();
            assertThatThrownBy(() -> jwtService.extractPrincipal(token)).isInstanceOf(IllegalArgumentException.class);
        }

        /**
         * Protects the mid claim alias extraction when token uses alternative claim key.
         */
        @Test
        @DisplayName("should extract membership ID when token uses mid claim key")
        void givenTokenWithMidClaim_whenExtractPrincipal_thenExtractMidAsMembershipId() {
            String token = io.jsonwebtoken.Jwts.builder()
                    .subject("Alice")
                    .claim("roomId", TEST_ROOM)
                    .claim("mid", "legacy-mid-123")
                    .signWith((SecretKey) ReflectionTestUtils.getField(jwtService, "secretKey"))
                    .compact();

            PlayerPrincipal principal = jwtService.extractPrincipal(token);

            assertThat(principal.membershipId()).isEqualTo("legacy-mid-123");
            assertThat(principal.getMembershipId()).isEqualTo("legacy-mid-123");
        }

        /**
         * Protects the contract for extractMembershipId helper method.
         */
        @Test
        @DisplayName("should extract membershipId directly via extractMembershipId")
        void givenTokenWithMembershipId_whenExtractMembershipId_thenReturnMembershipId() {
            String token = jwtService.generateToken("Alice", TEST_ROOM, "direct-mem-id");

            assertThat(jwtService.extractMembershipId(token)).isEqualTo("direct-mem-id");
        }

        /**
         * Protects extractMembershipId returning empty string when token lacks membership claim.
         */
        @Test
        @DisplayName("should return empty string from extractMembershipId when claim absent")
        void givenTokenWithoutMembershipId_whenExtractMembershipId_thenReturnEmptyString() {
            String token = jwtService.generateToken("Alice", TEST_ROOM, null);

            assertThat(jwtService.extractMembershipId(token)).isEmpty();
        }

        /**
         * Protects the contract that extractPlayerPrincipal and parseToken aliases behave identically to extractPrincipal.
         */
        @Test
        @DisplayName("should return identical principal via extractPlayerPrincipal and parseToken aliases")
        void givenToken_whenExtractPlayerPrincipalOrParseToken_thenReturnIdenticalPrincipal() {
            String token = jwtService.generateToken("Alice", TEST_ROOM, "alias-mem-id");

            PlayerPrincipal principal1 = jwtService.extractPrincipal(token);
            PlayerPrincipal principal2 = jwtService.extractPlayerPrincipal(token);
            PlayerPrincipal principal3 = jwtService.parseToken(token);

            assertThat(principal1).isEqualTo(principal2);
            assertThat(principal1).isEqualTo(principal3);
        }
    }

    /** Groups test scenarios for initialization. */
    @Nested
    @DisplayName("service initialization")
    class Initialization {

        /**
         * Protects the scenario: given valid secret, when initialise, then do not throw.
         * @param secret secret supplied to the fixture
         */
        @ParameterizedTest(name = "should accept secret \"{0}\"")
        @ValueSource(strings = {
                "Y2VudHVyeS1vbGQtc2VjcmV0LWtleS10aGF0LWlzLWRlZmluaXRlbHktbG9uZy1lbm91Z2gtZm9yLWhtYWMtc2hhLTUxMg==",
                "YW5vdGhlci12YWxpZC1zZWNyZXQtd2l0aC1zcGVjaWFsLWNoYXJzLWV2ZW4tdGhvdWdoLWl0LWlzLWJhc2U2NC1lbmNvZGVkISEh",
                "dmVyeS1sb25nLXNlY3JldC1rZXktdGhhdC1pcy1kZWZpbml0ZWx5LWxvbmctZW5vdWdoLTEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNA==",
                "MTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDEyMzQ1Ng=="
        })
        void givenValidSecret_whenInitialise_thenDoNotThrow(String secret) {
            JwtService service = new JwtService();
            ReflectionTestUtils.setField(service, "secretKeyString", secret);
            ReflectionTestUtils.setField(service, "expirationMillis", DEFAULT_EXPIRATION_MILLIS);

            Assertions.assertDoesNotThrow(service::init);
        }

        /**
         * Protects the contract that the system should reject a weak secret key.
         */
        @Test
        @DisplayName("should reject a weak secret key")
        void givenShortSecret_whenInitialise_thenThrowIllegalStateException() {
            JwtService service = new JwtService();
            ReflectionTestUtils.setField(service, "secretKeyString", "c2hvcnQ=");
            ReflectionTestUtils.setField(service, "expirationMillis", DEFAULT_EXPIRATION_MILLIS);

            assertThatThrownBy(service::init)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("parsing failed");
        }

        /**
         * Protects the scenario: given missing secret, when initialise, then throw illegal state exception.
         * @param secret secret supplied to the fixture
         */
        @ParameterizedTest(name = "should reject missing secret \"{0}\"")
        @NullAndEmptySource
        @ValueSource(strings = {"  ", "\t", "\n"})
        void givenMissingSecret_whenInitialise_thenThrowIllegalStateException(String secret) {
            JwtService service = new JwtService();
            ReflectionTestUtils.setField(service, "secretKeyString", secret);
            ReflectionTestUtils.setField(service, "expirationMillis", DEFAULT_EXPIRATION_MILLIS);

            assertThatThrownBy(service::init)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("missing");
        }
    }

    /** Groups test scenarios for PlayerPrincipal contract and equality. */
    @Nested
    @DisplayName("player principal contract")
    class PlayerPrincipalContract {

        @Test
        @DisplayName("should initialize with two arguments and set membershipId to null")
        void givenTwoArgConstructor_whenInstantiated_thenMembershipIdIsNull() {
            PlayerPrincipal principal = new PlayerPrincipal("Alice", "room-1");

            assertThat(principal.playerName()).isEqualTo("Alice");
            assertThat(principal.roomId()).isEqualTo("room-1");
            assertThat(principal.membershipId()).isNull();
            assertThat(principal.getPlayerName()).isEqualTo("Alice");
            assertThat(principal.getRoomId()).isEqualTo("room-1");
            assertThat(principal.getMembershipId()).isNull();
            assertThat(principal.getName()).isEqualTo("Alice:room-1:" + principal.membershipId());
        }

        @Test
        @DisplayName("should initialize with three arguments and set all fields")
        void givenThreeArgConstructor_whenInstantiated_thenAllFieldsSet() {
            PlayerPrincipal principal = new PlayerPrincipal("Alice", "room-1", "mem-1");

            assertThat(principal.playerName()).isEqualTo("Alice");
            assertThat(principal.roomId()).isEqualTo("room-1");
            assertThat(principal.membershipId()).isEqualTo("mem-1");
            assertThat(principal.getPlayerName()).isEqualTo("Alice");
            assertThat(principal.getRoomId()).isEqualTo("room-1");
            assertThat(principal.getMembershipId()).isEqualTo("mem-1");
            assertThat(principal.getName()).isEqualTo("Alice:room-1:" + principal.membershipId());
        }

        @Test
        @DisplayName("should be equal when all fields match")
        void givenIdenticalPrincipals_whenEqualsAndHashCode_thenAreEqual() {
            PlayerPrincipal p1 = new PlayerPrincipal("Alice", "room-1", "mem-1");
            PlayerPrincipal p2 = new PlayerPrincipal("Alice", "room-1", "mem-1");

            assertThat(p1).isEqualTo(p2);
            assertThat(p1.hashCode()).isEqualTo(p2.hashCode());
            assertThat(p1.toString()).contains("Alice", "room-1", "mem-1");
        }

        @Test
        @DisplayName("should not be equal when membershipId differs")
        void givenDifferentMembershipId_whenEquals_thenNotEqual() {
            PlayerPrincipal p1 = new PlayerPrincipal("Alice", "room-1", "mem-1");
            PlayerPrincipal p2 = new PlayerPrincipal("Alice", "room-1", "mem-2");

            assertThat(p1).isNotEqualTo(p2);
        }
    }

    /**
     * Protects the contract that the system should keep the generate validate extract lifecycle consistent.
     */
    @Test
    @DisplayName("should keep the generate validate extract lifecycle consistent")
    void givenGeneratedToken_whenRunningLifecycle_thenValidateAndExtractSuccessfully() {
        String token = jwtService.generateToken("TestPlayer", TEST_ROOM);

        assertThat(jwtService.isTokenValid(token)).isTrue();
        assertThat(jwtService.extractPlayerName(token)).isEqualTo("TestPlayer");
        
        PlayerPrincipal principal = jwtService.extractPrincipal(token);
        assertThat(principal.playerName()).isEqualTo("TestPlayer");
        assertThat(principal.roomId()).isEqualTo(TEST_ROOM);

        assertThat(token.split("\\.")).hasSize(3).allSatisfy(part -> assertThat(part).isNotBlank());
    }

    /**
     * Creates service for the test.
     * @param secret secret supplied to the fixture
     * @param expirationMillis expiration millis supplied to the fixture
     * @return initialized JWT service
     */
    private JwtService createService(String secret, long expirationMillis) {
        JwtService service = new JwtService();
        ReflectionTestUtils.setField(service, "secretKeyString", secret);
        ReflectionTestUtils.setField(service, "expirationMillis", expirationMillis);
        service.init();
        return service;
    }

    /**
     * Supports the test scenario for special player names.
     * @return player names that must round-trip through token subjects
     */
    static Stream<String> specialPlayerNames() {
        return Stream.of(
            "Player with spaces",
            "Player_with_underscores",
            "Player-with-dashes",
            "Player@Name.com",
            "A very long player name that exceeds normal limits but should still be supported by the token generator",
            "Player123!@#"
        );
    }
}
