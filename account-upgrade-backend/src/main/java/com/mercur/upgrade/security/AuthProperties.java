package com.mercur.upgrade.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Authentication settings ({@code upgrade.security}). Defaults are in {@code application.yml}; each environment
 * overrides them with environment variables (see PRODUCTION_GUARDRAILS.md).
 *
 * @param admin the single administrator allowed to log in
 * @param jwt   how access tokens are signed and for how long they are valid
 */
@Validated
@ConfigurationProperties("upgrade.security")
public record AuthProperties(@Valid @NotNull Admin admin, @Valid @NotNull Jwt jwt) {

    /** The development signing key in application.yml; the prod profile refuses to start with it. */
    public static final String DEVELOPMENT_SECRET = "dev-only-jwt-secret-change-me-0123456789abcdef";

    /**
     * @param username login name ({@code UPGRADE_SECURITY_ADMIN_USERNAME})
     * @param password login password ({@code UPGRADE_SECURITY_ADMIN_PASSWORD}); held only as a BCrypt hash at runtime
     */
    public record Admin(@NotBlank @Size(max = 64) String username, @NotBlank @Size(max = 72) String password) {

        @Override
        public String toString() {
            return "Admin[username=" + username + ", password=***]";
        }
    }

    /**
     * @param secret   HMAC-SHA256 signing key ({@code UPGRADE_SECURITY_JWT_SECRET}), at least 32 bytes
     * @param issuer   {@code iss} claim written into and required from every token
     * @param tokenTtl how long an access token is valid
     */
    public record Jwt(@NotBlank String secret, @NotBlank String issuer, @NotNull Duration tokenTtl) {

        /** HS256 needs a key of at least 256 bits. */
        @AssertTrue(message = "upgrade.security.jwt.secret must be at least 32 bytes")
        public boolean isSecretLongEnough() {
            return secret == null || secret.getBytes(StandardCharsets.UTF_8).length >= 32;
        }

        @AssertTrue(message = "upgrade.security.jwt.token-ttl must be between 1 minute and 24 hours")
        public boolean isTokenTtlReasonable() {
            return tokenTtl == null
                    || (tokenTtl.compareTo(Duration.ofMinutes(1)) >= 0 && tokenTtl.compareTo(Duration.ofHours(24)) <= 0);
        }

        @Override
        public String toString() {
            return "Jwt[secret=***, issuer=" + issuer + ", tokenTtl=" + tokenTtl + "]";
        }
    }
}
