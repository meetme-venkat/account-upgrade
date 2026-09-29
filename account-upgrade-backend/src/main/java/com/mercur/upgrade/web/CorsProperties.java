package com.mercur.upgrade.web;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Browser origins allowed to call the API directly. Empty (the default) disables CORS, which is
 * the right setting when the frontend reaches the API through its own reverse proxy.
 *
 * @param allowedOrigins e.g. {@code https://app.example.com}; set with {@code UPGRADE_WEB_CORS_ALLOWED_ORIGINS}
 */
@ConfigurationProperties("upgrade.web.cors")
public record CorsProperties(@DefaultValue List<String> allowedOrigins) {
}
