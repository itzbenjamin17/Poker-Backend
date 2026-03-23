package com.pokergame.security;

import io.jsonwebtoken.Jwts;
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

    @Value("${jwt.secret:dev-only-not-for-production-change-me}")
    private String secretKeyString;

    @Value("${jwt.expirationMillis:86400000}") // default 24h
    private long expirationMillis;

    private SecretKey secretKey;

    @PostConstruct
    public void init() {
        // Convert secret string to bytes for HMAC key
        // If the secret is already long enough (>= 32 bytes), use it directly
        // Otherwise, Keys.hmacShaKeyFor will throw WeakKeyException
        byte[] keyBytes = secretKeyString.getBytes();
        this.secretKey = Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * Generates a signed JWT for the given player.
     */
    public String generateToken(String playerName) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(playerName)
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
