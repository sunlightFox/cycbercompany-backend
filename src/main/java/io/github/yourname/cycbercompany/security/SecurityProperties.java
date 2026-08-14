package io.github.yourname.cycbercompany.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Shared ownership defaults for the open control plane. */
@ConfigurationProperties(prefix = "app.security")
public record SecurityProperties(String tenantId, String userId) {

    public SecurityProperties {
        tenantId = blankToDefault(tenantId, "local");
        userId = blankToDefault(userId, "local-user");
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
