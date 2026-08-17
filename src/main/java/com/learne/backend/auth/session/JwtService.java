package com.learne.backend.auth.session;

import com.learne.backend.domain.auth.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

@Service
class JwtService {

    private final AuthProperties authProperties;
    private final Clock clock;

    JwtService(AuthProperties authProperties) {
        this.authProperties = authProperties;
        this.clock = Clock.systemUTC();
    }

    String createAccessToken(User user) {
        Instant now = Instant.now(clock);
        Instant expiresAt = now.plus(authProperties.accessTokenTtl());
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim("role", user.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(secretKey())
                .compact();
    }

    Long parseUserId(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(secretKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return Long.valueOf(claims.getSubject());
    }

    long accessTokenTtlSeconds() {
        return authProperties.accessTokenTtl().toSeconds();
    }

    private SecretKey secretKey() {
        return Keys.hmacShaKeyFor(authProperties.jwtSecret().getBytes(StandardCharsets.UTF_8));
    }
}
