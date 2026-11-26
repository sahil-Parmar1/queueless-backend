package com.queueless.office_service.auth;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.queueless.office_service.provider.Provider;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

@Service
public class JwtService {

    private final SecretKey key;

    private final long expirationTime = 1000L * 60 * 60 * 24 * 30; // 30 days

    public JwtService(@Value("${jwt.secret:}") String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("jwt.secret (JWT_SECRET environment variable) must be configured");
        }
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException("jwt.secret must be at least 32 bytes for HMAC-SHA signing");
        }
        this.key = Keys.hmacShaKeyFor(keyBytes);
    }

    public String generateProviderToken(Provider provider, String officeId) {
        return Jwts.builder()
                .subject(provider.getUsername())
                .claim("providerId", provider.getId())
                .claim("username", provider.getUsername())
                .claim("name", provider.getName())
                .claim("officeId", officeId)
                .claim("officeProfileId", provider.getOffice() != null ? provider.getOffice().getId() : null)
                .claim("role", "PROVIDER")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationTime))
                .signWith(key)
                .compact();
    }

    public String extractEmail(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
    }

    public Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean isTokenValid(String token) {
        try {
            Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
