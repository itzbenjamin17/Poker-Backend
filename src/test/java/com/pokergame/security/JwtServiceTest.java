package com.pokergame.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pokergame.testutil.MutableClock;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.stream.Stream;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Unit tests for {@link JwtService}. */
@Tag("unit")
@DisplayName("JWT service")
class JwtServiceTest {

    private static final String TEST_SECRET =
            "Y2VudHVyeS1vbGQtc2VjcmV0LWtleS10aGF0LWlzLWRlZmluaXRlbHktbG9uZy1lbm91Z2gtZm9yLWhtYWMtc2hhLTUxMg==";
    private static final SecretKey TEST_SIGNING_KEY = Keys.hmacShaKeyFor(Decoders.BASE64.decode(TEST_SECRET));
    private static final long DEFAULT_EXPIRATION_MILLIS = 3_600_000L;
    private static final long INITIAL_EPOCH_MS = 1_700_000_000_000L;
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.ofEpochMilli(INITIAL_EPOCH_MS), ZoneOffset.UTC);
    private static final String TEST_ROOM = "test-room";

    private final JwtService jwtService = new JwtService(TEST_SECRET, DEFAULT_EXPIRATION_MILLIS, FIXED_CLOCK);

    @Nested
    @DisplayName("token generation")
    class TokenGeneration {

        @Test
        @DisplayName("should generate a non-empty valid token for a valid player name")
        void givenValidPlayerName_whenGenerateToken_thenReturnSignedToken() {
            String token = jwtService.generateToken("TestPlayer", TEST_ROOM);

            assertThat(token).isNotBlank();
            assertThat(jwtService.isTokenValid(token)).isTrue();
        }

        @Test
        @DisplayName("should generate different tokens for different players")
        void givenDifferentPlayers_whenGenerateToken_thenReturnDifferentTokens() {
            String firstToken = jwtService.generateToken("Player1", TEST_ROOM);
            String secondToken = jwtService.generateToken("Player2", TEST_ROOM);

            assertThat(firstToken).isNotEqualTo(secondToken);
        }

        @ParameterizedTest(name = "should preserve player name \"{0}\"")
        @MethodSource("com.pokergame.security.JwtServiceTest#specialPlayerNames")
        void givenSpecialOrLongPlayerNames_whenGenerateToken_thenPlayerNameCanBeExtracted(String playerName) {
            String token = jwtService.generateToken(playerName, TEST_ROOM);

            assertThat(jwtService.isTokenValid(token)).isTrue();
            assertThat(jwtService.extractPlayerName(token)).isEqualTo(playerName);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("should reject a token with missing or blank subject even when roomId and membershipId are valid")
        void givenMissingOrBlankSubject_whenValidateAndExtract_thenReject(String subject) {
            String token = Jwts.builder()
                    .subject(subject)
                    .claim("roomId", TEST_ROOM)
                    .claim("membershipId", "mem-1")
                    .signWith(TEST_SIGNING_KEY)
                    .compact();

            assertThat(jwtService.isTokenValid(token)).isFalse();
            assertThatThrownBy(() -> jwtService.extractPrincipal(token))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Token is missing required player or room identification claims.");
        }

        @Test
        @DisplayName("should reject a token with missing roomId claim")
        void givenMissingRoomId_whenValidate_thenReturnFalse() {
            String token = Jwts.builder()
                    .subject("TestPlayer")
                    .claim("membershipId", "mem-1")
                    .signWith(TEST_SIGNING_KEY)
                    .compact();

            assertThat(jwtService.isTokenValid(token)).isFalse();
            assertThatThrownBy(() -> jwtService.extractPrincipal(token))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Token is missing required player or room identification claims.");
        }

        @Test
        @DisplayName("should reject a token with blank roomId claim")
        void givenBlankRoomId_whenValidate_thenReturnFalse() {
            String token = Jwts.builder()
                    .subject("TestPlayer")
                    .claim("roomId", "   ")
                    .claim("membershipId", "mem-1")
                    .signWith(TEST_SIGNING_KEY)
                    .compact();

            assertThat(jwtService.isTokenValid(token)).isFalse();
            assertThatThrownBy(() -> jwtService.extractPrincipal(token))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Token is missing required player or room identification claims.");
        }

        @Test
        @DisplayName("should include membershipId claim when provided in token generation")
        void givenMembershipId_whenGenerateToken_thenTokenIncludesMembershipIdClaim() {
            String membershipId = "mem-session-12345";
            String token = jwtService.generateToken("TestPlayer", TEST_ROOM, membershipId);

            assertThat(token).isNotBlank();
            assertThat(jwtService.isTokenValid(token)).isTrue();

            var claims = Jwts.parser()
                    .verifyWith(TEST_SIGNING_KEY)
                    .clock(() -> Date.from(FIXED_CLOCK.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            assertThat(claims.get("membershipId", String.class)).isEqualTo(membershipId);
        }

        @Test
        @DisplayName("should produce distinct tokens for different membership IDs")
        void givenDifferentMembershipIds_whenGenerateToken_thenReturnDifferentTokens() {
            String firstToken = jwtService.generateToken("Player1", TEST_ROOM, "membership-A");
            String secondToken = jwtService.generateToken("Player1", TEST_ROOM, "membership-B");

            assertThat(firstToken).isNotEqualTo(secondToken);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("should omit membership claim when membership ID is null, empty, or blank")
        void givenBlankOrNullMembershipId_whenGenerateToken_thenOmitMembershipClaim(String membershipId) {
            String token = jwtService.generateToken("Player1", TEST_ROOM, membershipId);

            var claims = Jwts.parser()
                    .verifyWith(TEST_SIGNING_KEY)
                    .clock(() -> Date.from(FIXED_CLOCK.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            assertThat(claims.get("membershipId", String.class)).isNull();
        }
    }

    @Nested
    @DisplayName("token validation")
    class TokenValidation {

        @Test
        @DisplayName("should report valid tokens as valid")
        void givenFreshToken_whenValidate_thenReturnTrue() {
            String token = jwtService.generateToken("TestPlayer", TEST_ROOM);

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
            String token = jwtService.generateToken("TestPlayer", TEST_ROOM);
            String tamperedToken = token.substring(0, token.length() - 5) + "XXXXX";

            assertThat(jwtService.isTokenValid(tamperedToken)).isFalse();
        }

        @Test
        @DisplayName("should reject a token signed with a different secret")
        void givenTokenSignedWithDifferentSecret_whenValidate_thenReturnFalse() {
            JwtService otherService = new JwtService(
                    "YW5vdGhlci1kaWZmZXJlbnQtc2VjcmV0LWtleS10aGF0LWlzLWRlZmluaXRlbHktbG9uZy1lbm91Z2gtZm9yLWhtYWMtc2hhLTUxMg==",
                    DEFAULT_EXPIRATION_MILLIS,
                    FIXED_CLOCK);

            String token = otherService.generateToken("TestPlayer", TEST_ROOM);

            assertThat(jwtService.isTokenValid(token)).isFalse();
        }

        @Test
        @DisplayName("should accept token before expiry and reject after advancing MutableClock past expiry")
        void givenExpiredToken_whenValidate_thenReturnFalse() {
            MutableClock clock = new MutableClock(INITIAL_EPOCH_MS);
            JwtService timedService = new JwtService(TEST_SECRET, 5_000L, clock);
            String token = timedService.generateToken("TestPlayer", TEST_ROOM);

            clock.advanceByMillis(4_000L);
            assertThat(timedService.isTokenValid(token)).isTrue();

            clock.advanceByMillis(2_000L);
            assertThat(timedService.isTokenValid(token)).isFalse();
        }
    }

    @Nested
    @DisplayName("player extraction")
    class PlayerExtraction {

        @ParameterizedTest(name = "should extract player name \"{0}\"")
        @ValueSource(strings = {"Player1", "Player2", "Alice", "Bob", "Admin"})
        void givenValidToken_whenExtractPlayerName_thenReturnSubject(String playerName) {
            String token = jwtService.generateToken(playerName, TEST_ROOM);

            assertThat(jwtService.extractPlayerName(token)).isEqualTo(playerName);
        }

        @Test
        @DisplayName("should return empty string from extractPlayerName when subject claim is absent")
        void givenTokenWithoutSubject_whenExtractPlayerName_thenReturnEmptyString() {
            String token = Jwts.builder()
                    .claim("roomId", TEST_ROOM)
                    .claim("membershipId", "mem-1")
                    .signWith(TEST_SIGNING_KEY)
                    .compact();

            assertThat(jwtService.extractPlayerName(token)).isEmpty();
        }

        @Test
        @DisplayName("should extract full principal including room ID and non-blank membership ID")
        void givenValidToken_whenExtractPrincipal_thenReturnPlayerPrincipal() {
            String playerName = "Alice";
            String token = jwtService.generateToken(playerName, TEST_ROOM);

            PlayerPrincipal principal = jwtService.extractPrincipal(token);

            assertThat(principal.playerName()).isEqualTo(playerName);
            assertThat(principal.roomId()).isEqualTo(TEST_ROOM);
            assertThat(principal.membershipId()).isNotBlank();
            assertThat(principal.getName()).isEqualTo(playerName + ":" + TEST_ROOM + ":" + principal.membershipId());
        }

        @Test
        @DisplayName("should throw MalformedJwtException when extracting from a malformed token")
        void givenInvalidToken_whenExtractPlayerName_thenThrowMalformedJwtException() {
            assertThatThrownBy(() -> jwtService.extractPlayerName("invalid.token.string"))
                    .isInstanceOf(MalformedJwtException.class);
        }

        @Test
        @DisplayName("should throw ExpiredJwtException when the token has expired")
        void givenExpiredToken_whenExtractPlayerName_thenThrowExpiredJwtException() {
            MutableClock clock = new MutableClock(INITIAL_EPOCH_MS);
            JwtService timedService = new JwtService(TEST_SECRET, 5_000L, clock);
            String token = timedService.generateToken("TestPlayer", TEST_ROOM);

            clock.advanceByMillis(6_000L);
            assertThatThrownBy(() -> timedService.extractPlayerName(token))
                    .isInstanceOf(ExpiredJwtException.class);
        }

        @Test
        @DisplayName("should throw SignatureException when the token has been tampered with")
        void givenTamperedToken_whenExtractPlayerName_thenThrowSignatureException() {
            String token = jwtService.generateToken("TestPlayer", TEST_ROOM);
            String tamperedToken = token.substring(0, token.length() - 5) + "XXXXX";

            assertThatThrownBy(() -> jwtService.extractPlayerName(tamperedToken))
                    .isInstanceOf(SignatureException.class);
        }

        @Test
        @DisplayName("should extract principal recovering exact membership ID")
        void givenTokenWithMembershipId_whenExtractPrincipal_thenReturnPrincipalWithMembershipId() {
            String membershipId = "session-nonce-789";
            String token = jwtService.generateToken("Alice", TEST_ROOM, membershipId);

            PlayerPrincipal principal = jwtService.extractPrincipal(token);

            assertThat(principal.playerName()).isEqualTo("Alice");
            assertThat(principal.roomId()).isEqualTo(TEST_ROOM);
            assertThat(principal.membershipId()).isEqualTo(membershipId);
            assertThat(principal.getName()).isEqualTo("Alice:" + TEST_ROOM + ":" + membershipId);
        }

        @Test
        @DisplayName("should produce distinct principals for distinct membership IDs")
        void givenDifferentMembershipIds_whenExtractPrincipal_thenReturnDistinctPrincipals() {
            String tokenA = jwtService.generateToken("Alice", TEST_ROOM, "session-1");
            String tokenB = jwtService.generateToken("Alice", TEST_ROOM, "session-2");

            PlayerPrincipal principalA = jwtService.extractPrincipal(tokenA);
            PlayerPrincipal principalB = jwtService.extractPrincipal(tokenB);

            assertThat(principalA).isNotEqualTo(principalB);
            assertThat(principalA.membershipId()).isEqualTo("session-1");
            assertThat(principalB.membershipId()).isEqualTo("session-2");
        }

        @Test
        @DisplayName("should reject principal extraction when token lacks membership claim")
        void givenTokenWithoutMembershipId_whenExtractPrincipal_thenReject() {
            String token = jwtService.generateToken("Alice", TEST_ROOM, null);
            assertThat(jwtService.isTokenValid(token)).isFalse();
            assertThatThrownBy(() -> jwtService.extractPrincipal(token))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Token is missing required membership identification claim.");
        }

        @Test
        @DisplayName("should extract membership ID when token uses legacy mid claim key")
        void givenTokenWithMidClaim_whenExtractPrincipal_thenExtractMidAsMembershipId() {
            String token = Jwts.builder()
                    .subject("Alice")
                    .claim("roomId", TEST_ROOM)
                    .claim("mid", "legacy-mid-123")
                    .signWith(TEST_SIGNING_KEY)
                    .compact();

            PlayerPrincipal principal = jwtService.extractPrincipal(token);

            assertThat(principal.membershipId()).isEqualTo("legacy-mid-123");
        }

        @Test
        @DisplayName("should prefer membershipId over legacy mid claim when both are present")
        void givenTokenWithBothMembershipIdAndMid_whenExtractPrincipal_thenPreferMembershipId() {
            String token = Jwts.builder()
                    .subject("Alice")
                    .claim("roomId", TEST_ROOM)
                    .claim("membershipId", "primary-mem-id")
                    .claim("mid", "legacy-mid-id")
                    .signWith(TEST_SIGNING_KEY)
                    .compact();

            assertThat(jwtService.extractMembershipId(token)).isEqualTo("primary-mem-id");
            assertThat(jwtService.extractPrincipal(token).membershipId()).isEqualTo("primary-mem-id");
        }

        @Test
        @DisplayName("should extract membershipId directly via extractMembershipId")
        void givenTokenWithMembershipId_whenExtractMembershipId_thenReturnMembershipId() {
            String token = jwtService.generateToken("Alice", TEST_ROOM, "direct-mem-id");

            assertThat(jwtService.extractMembershipId(token)).isEqualTo("direct-mem-id");
        }

        @Test
        @DisplayName("should return empty string from extractMembershipId when claim absent")
        void givenTokenWithoutMembershipId_whenExtractMembershipId_thenReturnEmptyString() {
            String token = jwtService.generateToken("Alice", TEST_ROOM, null);

            assertThat(jwtService.extractMembershipId(token)).isEmpty();
        }

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

    @Nested
    @DisplayName("service initialization")
    class Initialization {

        @ParameterizedTest(name = "should accept secret \"{0}\"")
        @ValueSource(strings = {
                "Y2VudHVyeS1vbGQtc2VjcmV0LWtleS10aGF0LWlzLWRlZmluaXRlbHktbG9uZy1lbm91Z2gtZm9yLWhtYWMtc2hhLTUxMg==",
                "YW5vdGhlci12YWxpZC1zZWNyZXQtd2l0aC1zcGVjaWFsLWNoYXJzLWV2ZW4tdGhvdWdoLWl0LWlzLWJhc2U2NC1lbmNvZGVkISEh",
                "dmVyeS1sb25nLXNlY3JldC1rZXktdGhhdC1pcy1kZWZpbml0ZWx5LWxvbmctZW5vdWdoLTEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNA==",
                "MTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDEyMzQ1Ng=="
        })
        void givenValidSecret_whenInitialise_thenDoNotThrow(String secret) {
            assertThatCode(() -> new JwtService(secret, DEFAULT_EXPIRATION_MILLIS, FIXED_CLOCK))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("should reject a weak secret key")
        void givenShortSecret_whenInitialise_thenThrowIllegalStateException() {
            assertThatThrownBy(() -> new JwtService("c2hvcnQ=", DEFAULT_EXPIRATION_MILLIS, FIXED_CLOCK))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("JWT secret key parsing failed");
        }

        @Test
        @DisplayName("should reject a malformed non-Base64 secret key")
        void givenMalformedNonBase64Secret_whenInitialise_thenThrowIllegalStateException() {
            assertThatThrownBy(() -> new JwtService("%%%not-valid-base64%%%", DEFAULT_EXPIRATION_MILLIS, FIXED_CLOCK))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("JWT secret key parsing failed");
        }

        @ParameterizedTest(name = "should reject missing secret \"{0}\"")
        @NullAndEmptySource
        @ValueSource(strings = {"  ", "\t", "\n"})
        void givenMissingSecret_whenInitialise_thenThrowIllegalStateException(String secret) {
            assertThatThrownBy(() -> new JwtService(secret, DEFAULT_EXPIRATION_MILLIS, FIXED_CLOCK))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("JWT base64 secret key is missing");
        }
    }

    static Stream<String> specialPlayerNames() {
        return Stream.of(
                "Player with spaces",
                "Player_with_underscores",
                "Player-with-dashes",
                "Player@Name.com",
                "A very long player name that exceeds normal limits but should still be supported by the token generator",
                "Player123!@#");
    }
}
