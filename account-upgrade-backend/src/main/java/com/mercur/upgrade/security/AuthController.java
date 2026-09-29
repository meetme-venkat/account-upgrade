package com.mercur.upgrade.security;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;

/**
 * {@code POST /api/auth/login}: exchanges the administrator's username and password for a signed access token.
 * Wrong credentials get one generic {@code 401} (it doesn't say which part was wrong). Attempts are counted in the
 * {@code upgrade.auth.logins} metric by result, for alerting on password guessing.
 */
@RestController
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthenticationManager authenticationManager;
    private final AccessTokenService tokens;
    private final Counter succeeded;
    private final Counter failed;

    public AuthController(AuthenticationManager authenticationManager, AccessTokenService tokens,
                          MeterRegistry meterRegistry) {
        this.authenticationManager = authenticationManager;
        this.tokens = tokens;
        this.succeeded = loginCounter(meterRegistry, "success");
        this.failed = loginCounter(meterRegistry, "failure");
    }

    @PostMapping(SecurityConfig.LOGIN_PATH)
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        Authentication user = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(request.username(), request.password()));
        AccessTokenService.AccessToken token = tokens.issue(user.getName());
        succeeded.increment();
        log.info("User {} logged in; token valid until {}", user.getName(), token.expiresAt());
        return new LoginResponse(token.value(), "Bearer",
                Duration.between(token.issuedAt(), token.expiresAt()).toSeconds(), token.expiresAt());
    }

    @ExceptionHandler(AuthenticationException.class)
    ProblemDetail handleFailedLogin(AuthenticationException e) {
        failed.increment();
        log.warn("Failed login: {}", e.getClass().getSimpleName());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        problem.setTitle("Login failed");
        return problem;
    }

    private static Counter loginCounter(MeterRegistry registry, String result) {
        return Counter.builder("upgrade.auth.logins")
                .description("Login attempts by result")
                .tag("result", result)
                .register(registry);
    }

    /** Login form; limits keep the (deliberately slow) password hashing from being fed huge inputs. */
    public record LoginRequest(
            @NotBlank(message = "username is required") @Size(max = 64, message = "username must be at most 64 characters")
            String username,
            @NotBlank(message = "password is required") @Size(max = 72, message = "password must be at most 72 characters")
            String password) {

        @Override
        public String toString() {
            return "LoginRequest[username=" + username + ", password=***]";
        }
    }

    /**
     * @param accessToken the JWT to send as {@code Authorization: Bearer <accessToken>}
     * @param tokenType   always {@code Bearer}
     * @param expiresIn   seconds until the token expires
     * @param expiresAt   when the token expires
     */
    public record LoginResponse(String accessToken, String tokenType, long expiresIn, Instant expiresAt) {
    }
}
