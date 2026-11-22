package com.pokergame.security;

import static java.util.Objects.requireNonNull;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.time.Clock;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Service responsible for generating and validating JWT tokens for stateless
 * authentication.
 * No user accounts are required; the token's subject is the player's name.
 */
@Service
public class JwtService {
    private static final Logger logger = LoggerFactory.getLogger(JwtService.class);

    private final SecretKey secretKey;
    private final long expirationMillis;
    private final Clock clock;

    /**
     * Creates the JWT service and decodes the configured Base64 HMAC signing key.
     *
     * @param secretKeyString  Base64-encoded HMAC secret (at least 512 bits)
     * @param expirationMillis token validity duration in milliseconds
     * @param clock            clock used for token issuance and expiry validation
     * @throws IllegalStateException if the key is absent, malformed, or too weak
     */
    public JwtService(
            @Value("${app.jwt.base64-secret}") String secretKeyString,
            @Value("${jwt.expirationMillis:14400000}") long expirationMillis,
            Clock clock) {
        this.clock = requireNonNull(clock, "clock must not be null");
        this.expirationMillis = expirationMillis;
        if (secretKeyString == null || secretKeyString.isBlank()) {
            throw new IllegalStateException(
                    "JWT base64 secret key is missing. Please provide it via 'app.jwt.base64-secret' or the JWT_SECRET environment variable.");
        }
        try {
            byte[] keyBytes = Decoders.BASE64.decode(secretKeyString);
            this.secretKey = Keys.hmacShaKeyFor(keyBytes);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "JWT secret key parsing failed. Ensure it is a valid Base64 string and provides at least 512 bits of key material.",
                    e);
        }
    }

    /**
     * Generates a signed JWT for the given player and room.
     *
     * @param playerName player identity stored as the token subject
     * @param roomId room identity stored as a required claim
     * @return signed token with the configured expiry
     */
    public String generateToken(String playerName, String roomId) {
        return generateToken(playerName, roomId, UUID.randomUUID().toString());
    }

    /**
     * Generates a signed JWT for the given player, room, and membership ID.
     *
     * @param playerName player identity stored as the token subject
     * @param roomId room identity stored as a required claim
     * @param membershipId unique membership identity stored as a claim
     * @return signed token with the configured expiry
     */
    public String generateToken(String playerName, String roomId, String membershipId) {
        long now = clock.millis();
        var builder = Jwts.builder()
                .subject(playerName)
                .claim("roomId", roomId)
                .issuedAt(new Date(now))
                .expiration(new Date(now + expirationMillis));

        if (membershipId != null && !membershipId.isBlank()) {
            builder.claim("membershipId", membershipId);
        }

        return builder.signWith(secretKey).compact();
    }

    /**
     * Validates the token signature, expiry, and required claims.
     *
     * @param token encoded JWT to validate
     * @return {@code true} when the token is trusted and identifies a player and room
     */
    public boolean isTokenValid(String token) {
        try {
            Claims claims = parseClaims(token);
            String subject = claims.getSubject();
            String roomId = claims.get("roomId", String.class);
            String membershipId = resolveMembershipId(claims);
            return subject != null && !subject.isBlank() && roomId != null && !roomId.isBlank()
                    && membershipId != null && !membershipId.isBlank();
        } catch (Exception e) {
            logger.debug("JWT validation failed: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * Extracts the player principal from a valid token.
     *
     * @param token encoded, valid JWT
     * @return principal containing the player and room identities
     * @throws IllegalArgumentException if required claims are missing
     */
    public PlayerPrincipal extractPrincipal(String token) {
        Claims claims = parseClaims(token);
        String subject = claims.getSubject();
        String roomId = claims.get("roomId", String.class);

        if (subject == null || subject.isBlank() || roomId == null || roomId.isBlank()) {
            throw new IllegalArgumentException("Token is missing required player or room identification claims.");
        }

        String membershipId = resolveMembershipId(claims);
        if (membershipId == null || membershipId.isBlank()) {
            throw new IllegalArgumentException("Token is missing required membership identification claim.");
        }

        return new PlayerPrincipal(subject, roomId, membershipId);
    }

    /**
     * Alias for {@link #extractPrincipal(String)}.
     *
     * @param token encoded, valid JWT
     * @return principal containing player, room, and membership identities
     */
    public PlayerPrincipal extractPlayerPrincipal(String token) {
        return extractPrincipal(token);
    }

    /**
     * Alias for {@link #extractPrincipal(String)}.
     *
     * @param token encoded, valid JWT
     * @return principal containing player, room, and membership identities
     */
    public PlayerPrincipal parseToken(String token) {
        return extractPrincipal(token);
    }

    /**
     * Extracts the membership ID from a valid token.
     *
     * @param token encoded, valid JWT
     * @return membership ID, or an empty string when absent
     */
    public String extractMembershipId(String token) {
        String membershipId = resolveMembershipId(parseClaims(token));
        return membershipId == null ? "" : membershipId;
    }

    /**
     * Extracts the player name (subject) from a valid token.
     *
     * @param token encoded, valid JWT
     * @return token subject, or an empty string when the subject is absent
     */
    public String extractPlayerName(String token) {
        String subject = parseClaims(token).getSubject();
        return subject == null ? "" : subject;
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .clock(() -> Date.from(clock.instant()))
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    // Reads the current membership claim, accepting the legacy "mid" claim when present.
    private static String resolveMembershipId(Claims claims) {
        String membershipId = claims.get("membershipId", String.class);
        if (membershipId == null) {
            membershipId = claims.get("mid", String.class);
        }
        return membershipId;
    }
}
