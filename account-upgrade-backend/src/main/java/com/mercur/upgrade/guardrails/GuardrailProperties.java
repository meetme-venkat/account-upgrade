package com.mercur.upgrade.guardrails;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Production guardrail settings. See PRODUCTION_GUARDRAILS.md.
 *
 * @param rateLimit per-client request rate limit on {@code /api/**}
 */
@Validated
@ConfigurationProperties("upgrade.guardrails")
public record GuardrailProperties(
        @DefaultValue @Valid RateLimit rateLimit) {

    /**
     * Token bucket per client IP.
     *
     * @param requestsPerSecond sustained rate
     * @param burst             bucket size, i.e. requests allowed at once after a quiet period
     * @param maxTrackedClients cap on buckets kept in memory; idle buckets are evicted beyond it
     */
    public record RateLimit(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("20") @Min(1) int requestsPerSecond,
            @DefaultValue("40") @Min(1) int burst,
            @DefaultValue("10000") @Min(1) int maxTrackedClients) {
    }
}
