package com.mercur.upgrade.security;

import com.mercur.upgrade.IntegrationTestSupport;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Login issues a token; the API accepts only valid tokens: ours, unexpired, from our issuer, with scope api. */
@AutoConfigureMockMvc
class AuthenticationIntegrationTest extends IntegrationTestSupport {

    private static final String API = "/api/processed-upgrades";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private AuthProperties properties;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void loginWithTheAdministratorsCredentialsReturnsASignedTokenThatOpensTheApi() throws Exception {
        String body = mockMvc.perform(login("admin", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(3600))
                .andExpect(jsonPath("$.expiresAt").exists())
                .andReturn().getResponse().getContentAsString();
        String token = jsonMapper.readTree(body).get("accessToken").asString();

        Jwt jwt = jwtDecoder.decode(token);
        assertThat(jwt.getSubject()).isEqualTo("admin");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("account-upgrade-backend");
        assertThat(jwt.getClaimAsString("scope")).isEqualTo("api");
        assertThat(jwt.getId()).isNotBlank();
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofHours(1));

        mockMvc.perform(get(API).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andExpect(status().isOk());
    }

    @Test
    void wrongCredentialsGetOneGenericAnswerAndAreCounted() throws Exception {
        double failuresBefore = loginFailures();

        mockMvc.perform(login("admin", "wrong"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Login failed"))
                .andExpect(jsonPath("$.detail").value("Invalid username or password"))
                .andExpect(jsonPath("$.accessToken").doesNotExist());
        mockMvc.perform(login("root", "admin"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Invalid username or password"));

        assertThat(loginFailures() - failuresBefore).isEqualTo(2);
    }

    @Test
    void loginValidatesItsInput() throws Exception {
        mockMvc.perform(login("", "admin"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasItem("username: username is required")));
        mockMvc.perform(login("admin", "x".repeat(73)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasItem("password: password must be at most 72 characters")));
    }

    @Test
    void theApiRequiresAToken() throws Exception {
        mockMvc.perform(get(API))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.instance").value(API));
        mockMvc.perform(post("/api/realtime-upgrade").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"u1\",\"userName\":\"Ann\",\"age\":20,\"balance\":50}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsMalformedTamperedAndForeignTokens() throws Exception {
        String valid = jwtEncoder.encode(JwtEncoderParameters.from(jwsHeader(), claims(Instant.now(), "api").build()))
                .getTokenValue();
        String[] parts = valid.split("\\.");
        String tampered = parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"sub\":\"admin\",\"scope\":\"api\",\"iss\":\"account-upgrade-backend\",\"exp\":9999999999}"
                        .getBytes(StandardCharsets.UTF_8)) + "." + parts[2];
        String unsigned = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8)) + "." + parts[1] + ".";
        JwtEncoder otherKey = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(
                "another-signing-key-of-at-least-32-bytes".getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
        String foreign = otherKey.encode(JwtEncoderParameters.from(jwsHeader(), claims(Instant.now(), "api").build()))
                .getTokenValue();

        for (String token : new String[] {"not-a-jwt", tampered, unsigned, foreign}) {
            mockMvc.perform(get(API).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("invalid_token")))
                    .andExpect(jsonPath("$.detail").value("The access token is invalid or has expired. Log in again."));
        }
    }

    @Test
    void rejectsExpiredTokensAndTokensFromAnotherIssuer() throws Exception {
        Instant twoHoursAgo = Instant.now().minus(Duration.ofHours(2));
        String expired = jwtEncoder.encode(JwtEncoderParameters.from(jwsHeader(), claims(twoHoursAgo, "api").build()))
                .getTokenValue();
        String otherIssuer = jwtEncoder.encode(JwtEncoderParameters.from(jwsHeader(),
                claims(Instant.now(), "api").issuer("someone-else").build())).getTokenValue();

        mockMvc.perform(get(API).header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(API).header(HttpHeaders.AUTHORIZATION, "Bearer " + otherIssuer))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aValidTokenWithoutTheApiScopeIsForbidden() throws Exception {
        String noScope = jwtEncoder.encode(JwtEncoderParameters.from(jwsHeader(),
                claims(Instant.now(), "profile").build())).getTokenValue();

        mockMvc.perform(get(API).header(HttpHeaders.AUTHORIZATION, "Bearer " + noScope))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Forbidden"));
    }

    /** The 401 challenge points clients at this metadata document (RFC 9728), so it must be served. */
    @Test
    void describesItselfAsAnOAuthProtectedResource() throws Exception {
        mockMvc.perform(get("/.well-known/oauth-protected-resource"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resource").exists());
    }

    @Test
    void healthStaysPublicForProbesAndTheUi() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder login(
            String username, String password) {
        return post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password));
    }

    private static JwsHeader jwsHeader() {
        return JwsHeader.with(MacAlgorithm.HS256).build();
    }

    private JwtClaimsSet.Builder claims(Instant issuedAt, String scope) {
        return JwtClaimsSet.builder()
                .issuer(properties.jwt().issuer())
                .subject("admin")
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(Duration.ofHours(1)))
                .claim("scope", scope);
    }

    private double loginFailures() {
        return meterRegistry.get("upgrade.auth.logins").tag("result", "failure").counter().count();
    }
}
