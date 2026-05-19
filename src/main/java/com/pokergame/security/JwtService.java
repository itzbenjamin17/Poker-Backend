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
     * The subject is a composite of playerName and roomId to ensure global uniqueness
     * and prevent identity impersonation across different rooms.
     */
    public String generateToken(String playerName, String roomId) {
        long now = System.currentTimeMillis();
        String subject = playerName + ":" + roomId;
        return Jwts.builder()
                .subject(subject)
                .issuedAt(new Date(now))
                .expiration(new Date(now + expirationMillis))
                .signWith(secretKey)
                .compact();
    }

    /**
     * Validates the token signature and expiry.
     */
    public boolean isTokenValid(String token) {
        try {
            Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Extracts the player name (subject) from a valid token.
     */
    public String extractPlayerName(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
    }
}
