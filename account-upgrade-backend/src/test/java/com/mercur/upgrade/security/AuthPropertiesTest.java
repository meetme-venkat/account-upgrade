package com.mercur.upgrade.security;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Authentication settings are checked at startup, and never print secrets. */
class AuthPropertiesTest {

    private static final String SECRET = "a-signing-key-of-at-least-32-bytes!";

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @Test
    void acceptsTheDefaults() {
        assertThat(violations("admin", "admin", AuthProperties.DEVELOPMENT_SECRET, Duration.ofHours(1))).isEmpty();
    }

    @Test
    void requiresAHs256SizedSigningKey() {
        assertThat(messages(violations("admin", "admin", "too-short", Duration.ofHours(1))))
                .containsExactly("upgrade.security.jwt.secret must be at least 32 bytes");
    }

    @Test
    void boundsTheTokenLifetime() {
        assertThat(violations("admin", "admin", SECRET, Duration.ofSeconds(30))).isNotEmpty();
        assertThat(violations("admin", "admin", SECRET, Duration.ofDays(2))).isNotEmpty();
        assertThat(violations("admin", "admin", SECRET, Duration.ofHours(24))).isEmpty();
    }

    @Test
    void requiresCredentials() {
        assertThat(violations("", "admin", SECRET, Duration.ofHours(1))).isNotEmpty();
        assertThat(violations("admin", " ", SECRET, Duration.ofHours(1))).isNotEmpty();
    }

    @Test
    void neverPrintsThePasswordOrTheSigningKey() {
        AuthProperties properties = new AuthProperties(new AuthProperties.Admin("admin", "s3cret-password"),
                new AuthProperties.Jwt(SECRET, "issuer", Duration.ofHours(1)));

        assertThat(properties.toString()).doesNotContain("s3cret-password").doesNotContain(SECRET);
        assertThat(new AuthController.LoginRequest("admin", "s3cret-password").toString())
                .doesNotContain("s3cret-password");
    }

    private static Set<ConstraintViolation<AuthProperties>> violations(String username, String password,
                                                                       String secret, Duration ttl) {
        return validator.validate(new AuthProperties(new AuthProperties.Admin(username, password),
                new AuthProperties.Jwt(secret, "account-upgrade-backend", ttl)));
    }

    private static Set<String> messages(Set<ConstraintViolation<AuthProperties>> violations) {
        return violations.stream().map(ConstraintViolation::getMessage).collect(java.util.stream.Collectors.toSet());
    }
}
