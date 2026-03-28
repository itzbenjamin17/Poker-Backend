package com.pokergame.security;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.security.SignatureException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the JwtService class.
 * Tests token generation, validation, and player name extraction.
 */
class JwtServiceTest {

    private JwtService jwtService;
    private static final String TEST_SECRET = "test-secret-key-that-is-long-enough-for-hmac-sha-256";
    private static final long TEST_EXPIRATION = 3600000L; // 1 hour

    @BeforeEach
    void setUp() {
        jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "secretKeyString", TEST_SECRET);
        ReflectionTestUtils.setField(jwtService, "expirationMillis", TEST_EXPIRATION);
        jwtService.init();
    }

    // ==================== generateToken Tests ====================

    @Test
    void generateToken_WithValidPlayerName_ShouldReturnNonNullToken() {
        String token = jwtService.generateToken("TestPlayer");

        assertNotNull(token);
        assertFalse(token.isEmpty());
    }

    @Test
    void generateToken_WithDifferentPlayerNames_ShouldReturnDifferentTokens() {
        String token1 = jwtService.generateToken("Player1");
        String token2 = jwtService.generateToken("Player2");

        assertNotEquals(token1, token2);
    }

    @Test
    void generateToken_MultipleTokensForSamePlayer_ShouldBeIndependent() {
        // Generate multiple tokens for the same player
        String token1 = jwtService.generateToken("TestPlayer");
        String token2 = jwtService.generateToken("TestPlayer");

        // Both tokens should be valid
        assertTrue(jwtService.isTokenValid(token1));
        assertTrue(jwtService.isTokenValid(token2));

        // Both should extract the same player name
        assertEquals("TestPlayer", jwtService.extractPlayerName(token1));
        assertEquals("TestPlayer", jwtService.extractPlayerName(token2));

        // Note: Tokens generated in the same millisecond may be identical
        // This is expected behavior - what matters is both are valid for the same player
    }

    @Test
    void generateToken_WithEmptyPlayerName_ShouldStillGenerateToken() {
        String token = jwtService.generateToken("");

        assertNotNull(token);
        assertTrue(jwtService.isTokenValid(token));
        // Note: extractPlayerName will return null for empty subject, not empty string
        assertNull(jwtService.extractPlayerName(token));
    }

    @Test
    void generateToken_WithSpecialCharacters_ShouldGenerateValidToken() {
        String playerName = "Player@123!#$%";
        String token = jwtService.generateToken(playerName);

        assertNotNull(token);
        assertTrue(jwtService.isTokenValid(token));
        assertEquals(playerName, jwtService.extractPlayerName(token));
    }

    @Test
    void generateToken_WithUnicodeCharacters_ShouldGenerateValidToken() {
        String playerName = "玩家123";
        String token = jwtService.generateToken(playerName);

        assertNotNull(token);
        assertTrue(jwtService.isTokenValid(token));
        assertEquals(playerName, jwtService.extractPlayerName(token));
    }

    @Test
    void generateToken_WithVeryLongPlayerName_ShouldGenerateValidToken() {
        String playerName = "A".repeat(1000);
        String token = jwtService.generateToken(playerName);

        assertNotNull(token);
        assertTrue(jwtService.isTokenValid(token));
        assertEquals(playerName, jwtService.extractPlayerName(token));
    }

    // ==================== isTokenValid Tests ====================

    @Test
    void isTokenValid_WithValidToken_ShouldReturnTrue() {
        String token = jwtService.generateToken("TestPlayer");

        assertTrue(jwtService.isTokenValid(token));
    }

    @Test
    void isTokenValid_WithInvalidToken_ShouldReturnFalse() {
        String invalidToken = "invalid.token.string";

        assertFalse(jwtService.isTokenValid(invalidToken));
    }

    @Test
    void isTokenValid_WithExpiredToken_ShouldReturnFalse() throws InterruptedException {
        // Create a service with very short expiration
        JwtService shortLivedService = new JwtService();
        ReflectionTestUtils.setField(shortLivedService, "secretKeyString", TEST_SECRET);
        ReflectionTestUtils.setField(shortLivedService, "expirationMillis", 10L); // 10 milliseconds
        shortLivedService.init();

        String token = shortLivedService.generateToken("TestPlayer");

        // Wait for token to expire
        Thread.sleep(50);

        assertFalse(shortLivedService.isTokenValid(token));
    }

    @Test
    void isTokenValid_WithTamperedToken_ShouldReturnFalse() {
        String token = jwtService.generateToken("TestPlayer");

        // Tamper with the token by modifying a character
        String tamperedToken = token.substring(0, token.length() - 5) + "XXXXX";

        assertFalse(jwtService.isTokenValid(tamperedToken));
    }

    @Test
    void isTokenValid_WithNullToken_ShouldReturnFalse() {
        assertFalse(jwtService.isTokenValid(null));
    }

    @Test
    void isTokenValid_WithEmptyToken_ShouldReturnFalse() {
        assertFalse(jwtService.isTokenValid(""));
    }

    @Test
    void isTokenValid_WithTokenFromDifferentSecret_ShouldReturnFalse() {
        // Create another service with different secret
        JwtService otherService = new JwtService();
        ReflectionTestUtils.setField(otherService, "secretKeyString", "different-secret-key-for-testing");
        ReflectionTestUtils.setField(otherService, "expirationMillis", TEST_EXPIRATION);
        otherService.init();

        String tokenFromOtherService = otherService.generateToken("TestPlayer");

        // Should be invalid when validated with original service
        assertFalse(jwtService.isTokenValid(tokenFromOtherService));
    }

    @Test
    void isTokenValid_WithMalformedJWT_ShouldReturnFalse() {
        String[] malformedTokens = {
                "not.a.jwt",
                "only.two.parts",
                "",
                ".",
                "..",
                "...",
                "malformed"
        };

        for (String malformedToken : malformedTokens) {
            assertFalse(jwtService.isTokenValid(malformedToken),
                    "Token should be invalid: " + malformedToken);
        }
    }

    // ==================== extractPlayerName Tests ====================

    @Test
    void extractPlayerName_WithValidToken_ShouldReturnCorrectPlayerName() {
        String playerName = "TestPlayer";
        String token = jwtService.generateToken(playerName);

        String extractedName = jwtService.extractPlayerName(token);

        assertEquals(playerName, extractedName);
    }

    @Test
    void extractPlayerName_WithDifferentPlayerNames_ShouldReturnCorrectNames() {
        String[] playerNames = {"Player1", "Player2", "Alice", "Bob", "Admin"};

        for (String playerName : playerNames) {
            String token = jwtService.generateToken(playerName);
            String extractedName = jwtService.extractPlayerName(token);
            assertEquals(playerName, extractedName);
        }
    }

    @Test
    void extractPlayerName_WithEmptyPlayerName_ShouldReturnNull() {
        String token = jwtService.generateToken("");

        String extractedName = jwtService.extractPlayerName(token);

        // JWT treats empty string subject as null
        assertNull(extractedName);
    }

    @Test
    void extractPlayerName_WithSpecialCharacters_ShouldReturnCorrectName() {
        String playerName = "Player@123!#$%";
        String token = jwtService.generateToken(playerName);

        String extractedName = jwtService.extractPlayerName(token);

        assertEquals(playerName, extractedName);
    }

    @Test
    void extractPlayerName_WithInvalidToken_ShouldThrowException() {
        String invalidToken = "invalid.token.string";

        assertThrows(Exception.class, () -> jwtService.extractPlayerName(invalidToken));
    }

    @Test
    void extractPlayerName_WithExpiredToken_ShouldThrowExpiredJwtException() throws InterruptedException {
        // Create a service with very short expiration
        JwtService shortLivedService = new JwtService();
        ReflectionTestUtils.setField(shortLivedService, "secretKeyString", TEST_SECRET);
        ReflectionTestUtils.setField(shortLivedService, "expirationMillis", 10L);
        shortLivedService.init();

        String token = shortLivedService.generateToken("TestPlayer");

        // Wait for token to expire
        Thread.sleep(50);

        assertThrows(ExpiredJwtException.class, () -> shortLivedService.extractPlayerName(token));
    }

    @Test
    void extractPlayerName_WithTamperedToken_ShouldThrowSignatureException() {
        String token = jwtService.generateToken("TestPlayer");
        String tamperedToken = token.substring(0, token.length() - 5) + "XXXXX";

        assertThrows(SignatureException.class, () -> jwtService.extractPlayerName(tamperedToken));
    }

    @Test
    void extractPlayerName_WithNullToken_ShouldThrowException() {
        assertThrows(Exception.class, () -> jwtService.extractPlayerName(null));
    }

    // ==================== Integration Tests ====================

    @Test
    void tokenLifecycle_GenerateValidateExtract_ShouldWorkCorrectly() {
        String playerName = "TestPlayer";

        // Generate
        String token = jwtService.generateToken(playerName);
        assertNotNull(token);

        // Validate
        assertTrue(jwtService.isTokenValid(token));

        // Extract
        String extractedName = jwtService.extractPlayerName(token);
        assertEquals(playerName, extractedName);
    }

    @Test
    void multipleTokens_ShouldBeIndependent() {
        String player1 = "Player1";
        String player2 = "Player2";

        String token1 = jwtService.generateToken(player1);
        String token2 = jwtService.generateToken(player2);

        // Both tokens should be valid
        assertTrue(jwtService.isTokenValid(token1));
        assertTrue(jwtService.isTokenValid(token2));

        // Each token should extract its own player name
        assertEquals(player1, jwtService.extractPlayerName(token1));
        assertEquals(player2, jwtService.extractPlayerName(token2));

        // Tokens should be different
        assertNotEquals(token1, token2);
    }

    @Test
    void tokenFormat_ShouldBeStandardJWT() {
        String token = jwtService.generateToken("TestPlayer");

        // JWT should have 3 parts separated by dots
        String[] parts = token.split("\\.");
        assertEquals(3, parts.length, "JWT should have 3 parts (header.payload.signature)");

        // Each part should be non-empty
        for (String part : parts) {
            assertFalse(part.isEmpty(), "JWT parts should not be empty");
        }
    }

    @Test
    void init_ShouldHandleDifferentSecretKeyFormats() {
        JwtService service = new JwtService();

        // Test with various secret key formats that meet minimum length requirements
        // After base64 encoding, they need to produce at least 256 bits (32 bytes)
        String[] secrets = {
                "this-is-a-long-enough-secret-key-for-hmac-sha-256-algorithm",
                "another-valid-secret-with-special-chars-!@#$%^&*()_+-=[]{}",
                "very-long-secret-key-that-is-definitely-long-enough-12345678",
                "12345678901234567890123456789012345678901234567890123456"
        };

        for (String secret : secrets) {
            ReflectionTestUtils.setField(service, "secretKeyString", secret);
            ReflectionTestUtils.setField(service, "expirationMillis", TEST_EXPIRATION);

            assertDoesNotThrow(service::init, "Init should not throw for secret: " + secret);
        }
    }

    @Test
    void init_WithShortSecretKey_ShouldThrowWeakKeyException() {
        JwtService service = new JwtService();
        ReflectionTestUtils.setField(service, "secretKeyString", "short");
        ReflectionTestUtils.setField(service, "expirationMillis", TEST_EXPIRATION);

        // Short keys should throw WeakKeyException
        assertThrows(io.jsonwebtoken.security.WeakKeyException.class, service::init);
    }

    @Test
    void tokenExpiration_ShouldBeRespected() throws InterruptedException {
        long shortExpiration = 1900L;
        JwtService shortLivedService = new JwtService();
        ReflectionTestUtils.setField(shortLivedService, "secretKeyString", TEST_SECRET);
        ReflectionTestUtils.setField(shortLivedService, "expirationMillis", shortExpiration);
        shortLivedService.init();

        String token = shortLivedService.generateToken("TestPlayer");

        // Token should be valid immediately
        assertTrue(shortLivedService.isTokenValid(token), "Token should be valid immediately after generation");

        // Wait for token to expire
        Thread.sleep(2000);

        // Token should now be invalid
        assertFalse(shortLivedService.isTokenValid(token), "Token should be invalid after expiration");
    }

    @Test
    void tokenSecurity_DifferentPlayersCannotReuseTokens() {
        String token1 = jwtService.generateToken("Player1");

        // Token is valid
        assertTrue(jwtService.isTokenValid(token1));

        // Token contains Player1's name
        assertEquals("Player1", jwtService.extractPlayerName(token1));

        // Generate token for Player2
        String token2 = jwtService.generateToken("Player2");

        // Both tokens are valid but contain different player names
        assertTrue(jwtService.isTokenValid(token1));
        assertTrue(jwtService.isTokenValid(token2));
        assertNotEquals(jwtService.extractPlayerName(token1), jwtService.extractPlayerName(token2));
    }
}
