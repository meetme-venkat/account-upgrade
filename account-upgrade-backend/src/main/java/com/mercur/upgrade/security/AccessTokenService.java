package com.mercur.upgrade.security;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Issues the signed JWT access tokens that {@link SecurityConfig} requires on every {@code /api} request.
 * Claims: {@code iss}, {@code sub} (the user), {@code iat}, {@code exp}, a unique {@code jti} and
 * {@code scope=api}.
 */
@Component
public class AccessTokenService {

    /** Scope every API call requires; Spring Security exposes it as the authority {@code SCOPE_api}. */
    static final String API_SCOPE = "api";

    private final JwtEncoder encoder;
    private final AuthProperties.Jwt jwt;
    private final Clock clock;

    public AccessTokenService(JwtEncoder encoder, AuthProperties properties, Clock clock) {
        this.encoder = encoder;
        this.jwt = properties.jwt();
        this.clock = clock;
    }

    /** A new access token for {@code username}, valid for {@code upgrade.security.jwt.token-ttl}. */
    public AccessToken issue(String username) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(jwt.tokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwt.issuer())
                .subject(username)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim("scope", API_SCOPE)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new AccessToken(value, issuedAt, expiresAt);
    }

    /** A signed token and its validity window. */
    public record AccessToken(String value, Instant issuedAt, Instant expiresAt) {

        @Override
        public String toString() {
            return "AccessToken[value=***, issuedAt=" + issuedAt + ", expiresAt=" + expiresAt + "]";
        }
    }
}
