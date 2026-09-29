package com.mercur.upgrade.guardrails;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers the guardrail filters, outermost first: request id (so every later log line carries
 * it), security headers (so even rejected responses carry them), then the rate limit.
 */
@Configuration(proxyBeanMethods = false)
public class GuardrailFilterConfig {

    @Bean
    FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
        return register(new RequestIdFilter(), Ordered.HIGHEST_PRECEDENCE);
    }

    @Bean
    FilterRegistrationBean<SecurityHeadersFilter> securityHeadersFilter() {
        return register(new SecurityHeadersFilter(), Ordered.HIGHEST_PRECEDENCE + 1);
    }

    @Bean
    @ConditionalOnProperty(name = "upgrade.guardrails.rate-limit.enabled", havingValue = "true")
    FilterRegistrationBean<RateLimitFilter> rateLimitFilter(GuardrailProperties properties) {
        return register(new RateLimitFilter(properties.rateLimit()), Ordered.HIGHEST_PRECEDENCE + 2);
    }

    private static <T extends jakarta.servlet.Filter> FilterRegistrationBean<T> register(T filter, int order) {
        FilterRegistrationBean<T> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(order);
        return registration;
    }
}
