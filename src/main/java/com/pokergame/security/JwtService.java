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

    @Value("${jwt.secret}")
    private String secretKeyString;

    @Value("${jwt.expirationMillis:86400000}") // default 24h
    private long expirationMillis;

    private SecretKey secretKey;

    @PostConstruct
    public void init() {
        if (secretKeyString == null || secretKeyString.isBlank()) {
            throw new IllegalStateException("JWT secret key is missing. Please provide a valid secret via 'jwt.secret' or JWT_SECRET environment variable.");
        }

        try {
            // Convert secret string to bytes for HMAC key
            byte[] keyBytes = secretKeyString.getBytes();
            this.secretKey = Keys.hmacShaKeyFor(keyBytes);
        } catch (Exception e) {
            throw new IllegalStateException("JWT secret key is too weak or invalid. It must be at least 256 bits (32 characters for plain text).", e);
        }
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
