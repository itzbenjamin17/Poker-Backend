package com.pokergame.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Date;

/**
 * Service responsible for generating and validating JWT tokens for stateless
 * authentication.
 * No user accounts are required; the token's subject is the player's name.
 */
@Service
public class JwtService {

    @Value("${app.jwt.base64-secret}")
    private String secretKeyString;

    @Value("${jwt.expirationMillis:14400000}") // default 4h
    private long expirationMillis;

    private SecretKey secretKey;

    /**
     * Decodes and validates the configured signing key after dependency injection.
     *
     * @throws IllegalStateException if the key is absent, malformed, or too weak
     */
    @PostConstruct
    public void init() {
        if (secretKeyString == null || secretKeyString.isBlank()) {
            throw new IllegalStateException("JWT base64 secret key is missing. Please provide it via 'app.jwt.base64-secret' or the JWT_SECRET environment variable.");
        }

        try {
            // Decode the Base64 string directly into a byte array
            byte[] keyBytes = Decoders.BASE64.decode(secretKeyString);
            this.secretKey = Keys.hmacShaKeyFor(keyBytes);
        } catch (Exception e) {
            throw new IllegalStateException("JWT secret key parsing failed. Ensure it is a valid Base64 string and provides at least 512 bits of key material.", e);        }
    }

    /**
     * Generates a signed JWT for the given player and room.
     *
     * @param playerName player identity stored as the token subject
     * @param roomId room identity stored as a required claim
     * @return signed token with the configured expiry
     */
    public String generateToken(String playerName, String roomId) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(playerName)
                .claim("roomId", roomId)
                .issuedAt(new Date(now))
                .expiration(new Date(now + expirationMillis))
                .signWith(secretKey)
                .compact();
    }

    /**
     * Validates the token signature, expiry, and required claims.
     *
     * @param token encoded JWT to validate
     * @return {@code true} when the token is trusted and identifies a player and room
     */
    public boolean isTokenValid(String token) {
        try {
            var claims = Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token).getPayload();
            String subject = claims.getSubject();
            String roomId = claims.get("roomId", String.class);
            return subject != null && !subject.isBlank() && roomId != null && !roomId.isBlank();
        } catch (Exception e) {
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
        var claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        
        String subject = claims.getSubject();
        String roomId = claims.get("roomId", String.class);
        
        if (subject == null || subject.isBlank() || roomId == null || roomId.isBlank()) {
            throw new IllegalArgumentException("Token is missing required player or room identification claims.");
        }
        
        return new PlayerPrincipal(subject, roomId);
    }

    /**
     * Extracts the player name (subject) from a valid token.
     *
     * @param token encoded, valid JWT
     * @return token subject, or an empty string when the subject is absent
     */
    public String extractPlayerName(String token) {
        String subject = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
        return subject == null ? "" : subject;
    }
}
