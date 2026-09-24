package com.fieldwork.ops.common.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CORS settings, bound from {@code app.cors} in application.yml.
 *
 * <p>The SPA origin allowlist comes from the
 * {@code APP_CORS_ALLOWED_ORIGINS} environment variable
 * (comma-separated); it defaults to the local Vite dev server.
 */
@ConfigurationProperties(prefix = "app.cors")
public record CorsProperties(List<String> allowedOrigins) {

    public CorsProperties {
        if (allowedOrigins == null || allowedOrigins.isEmpty()) {
            allowedOrigins = List.of("http://localhost:5173");
        }
    }
}
