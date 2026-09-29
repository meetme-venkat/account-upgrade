package com.mercur.upgrade.guardrails;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitFilterTest {

    private final AtomicLong now = new AtomicLong();

    private RateLimitFilter filter(int rps, int burst, int maxClients) {
        return new RateLimitFilter(new GuardrailProperties.RateLimit(true, rps, burst, maxClients), now::get);
    }

    private static MockHttpServletResponse call(RateLimitFilter filter, String ip, String uri) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRemoteAddr(ip);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void allowsTheBurstThenRejectsWithRetryAfter() throws Exception {
        RateLimitFilter filter = filter(1, 3, 100);

        for (int i = 0; i < 3; i++) {
            assertThat(call(filter, "10.0.0.1", "/api/realtime-upgrade").getStatus()).isEqualTo(200);
        }
        MockHttpServletResponse rejected = call(filter, "10.0.0.1", "/api/realtime-upgrade");

        assertThat(rejected.getStatus()).isEqualTo(429);
        assertThat(rejected.getHeader("Retry-After")).isEqualTo("1");
        assertThat(rejected.getContentType()).isEqualTo("application/problem+json");
        assertThat(rejected.getContentAsString()).contains("\"status\":429");
    }

    @Test
    void refillsOverTime() throws Exception {
        RateLimitFilter filter = filter(2, 1, 100);
        assertThat(call(filter, "10.0.0.1", "/api/x").getStatus()).isEqualTo(200);
        assertThat(call(filter, "10.0.0.1", "/api/x").getStatus()).isEqualTo(429);

        now.addAndGet(TimeUnit.MILLISECONDS.toNanos(500));

        assertThat(call(filter, "10.0.0.1", "/api/x").getStatus()).isEqualTo(200);
    }

    @Test
    void limitsEachClientSeparately() throws Exception {
        RateLimitFilter filter = filter(1, 1, 100);
        assertThat(call(filter, "10.0.0.1", "/api/x").getStatus()).isEqualTo(200);
        assertThat(call(filter, "10.0.0.1", "/api/x").getStatus()).isEqualTo(429);

        assertThat(call(filter, "10.0.0.2", "/api/x").getStatus()).isEqualTo(200);
    }

    @Test
    void ignoresNonApiPathsSuchAsHealthChecks() throws Exception {
        RateLimitFilter filter = filter(1, 1, 100);
        for (int i = 0; i < 5; i++) {
            assertThat(call(filter, "10.0.0.1", "/actuator/health").getStatus()).isEqualTo(200);
        }
        assertThat(filter.trackedClients()).isZero();
    }

    @Test
    void keepsTrackedClientsBoundedUnderManyDistinctIps() throws Exception {
        RateLimitFilter filter = filter(10, 10, 50);
        for (int i = 0; i < 1_000; i++) {
            call(filter, "10.0." + (i / 256) + "." + (i % 256), "/api/x");
        }
        assertThat(filter.trackedClients()).isLessThanOrEqualTo(50);
    }
}
