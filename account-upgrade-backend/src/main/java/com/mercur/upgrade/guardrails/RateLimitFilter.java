package com.mercur.upgrade.guardrails;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Limits each client IP to a sustained rate on {@code /api/**}, answering {@code 429} with
 * {@code Retry-After} beyond it. Protects the broker's bounded queues from a single noisy client.
 *
 * <p>The client IP is {@link HttpServletRequest#getRemoteAddr()}; behind a reverse proxy, the
 * {@code prod} profile sets {@code server.forward-headers-strategy=native} so it reflects
 * {@code X-Forwarded-For}. State is per instance: N replicas allow N times the rate.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final long NANOS_PER_SECOND = TimeUnit.SECONDS.toNanos(1);

    private final GuardrailProperties.RateLimit limits;
    private final LongSupplier nanoClock;
    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(GuardrailProperties.RateLimit limits) {
        this(limits, System::nanoTime);
    }

    RateLimitFilter(GuardrailProperties.RateLimit limits, LongSupplier nanoClock) {
        this.limits = limits;
        this.nanoClock = nanoClock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long now = nanoClock.getAsLong();
        evictIdleBucketsIfFull(now);
        TokenBucket bucket = buckets.computeIfAbsent(request.getRemoteAddr(), ip -> new TokenBucket(now));
        long waitNanos = bucket.tryConsume(now);
        if (waitNanos == 0) {
            chain.doFilter(request, response);
            return;
        }
        long retryAfterSeconds = Math.max(1, (waitNanos + NANOS_PER_SECOND - 1) / NANOS_PER_SECOND);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("""
                {"status":429,"title":"Too many requests","detail":"Rate limit exceeded, retry after %d second(s)"}"""
                .formatted(retryAfterSeconds));
    }

    int trackedClients() {
        return buckets.size();
    }

    /** Keeps memory bounded under many distinct IPs by dropping buckets that have refilled completely. */
    private void evictIdleBucketsIfFull(long now) {
        if (buckets.size() >= limits.maxTrackedClients()) {
            buckets.values().removeIf(bucket -> bucket.isFull(now));
            if (buckets.size() >= limits.maxTrackedClients()) {
                buckets.clear();
            }
        }
    }

    private final class TokenBucket {

        private double tokens;
        private long lastRefill;

        TokenBucket(long now) {
            this.tokens = limits.burst();
            this.lastRefill = now;
        }

        /** Takes a token and returns 0, or returns the nanoseconds until one is available. */
        synchronized long tryConsume(long now) {
            refill(now);
            if (tokens >= 1) {
                tokens -= 1;
                return 0;
            }
            return (long) Math.ceil((1 - tokens) * NANOS_PER_SECOND / limits.requestsPerSecond());
        }

        synchronized boolean isFull(long now) {
            refill(now);
            return tokens >= limits.burst();
        }

        private void refill(long now) {
            double added = (double) (now - lastRefill) * limits.requestsPerSecond() / NANOS_PER_SECOND;
            tokens = Math.min(limits.burst(), tokens + added);
            lastRefill = now;
        }
    }
}
