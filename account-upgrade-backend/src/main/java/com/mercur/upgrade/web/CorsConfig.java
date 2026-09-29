package com.mercur.upgrade.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.time.Duration;
import java.util.List;

/**
 * Lets a separately hosted frontend call {@code /api/**} from the configured origins. Applied by the security
 * filter chain ({@code SecurityConfig}), so preflight requests are answered before authentication. No origins
 * configured (the default) means no cross-origin access.
 */
@Configuration(proxyBeanMethods = false)
public class CorsConfig {

    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        if (properties.allowedOrigins().isEmpty()) {
            return source;
        }
        CorsConfiguration api = new CorsConfiguration();
        api.setAllowedOrigins(properties.allowedOrigins());
        api.setAllowedMethods(List.of("GET", "POST"));
        api.setAllowedHeaders(List.of("Content-Type", "Idempotency-Key", "Authorization"));
        api.setMaxAge(Duration.ofHours(1));
        source.registerCorsConfiguration("/api/**", api);
        return source;
    }
}
