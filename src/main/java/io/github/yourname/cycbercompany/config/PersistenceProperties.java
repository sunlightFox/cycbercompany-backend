package io.github.yourname.cycbercompany.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Persistence failure policy for supervised deployments. */
@ConfigurationProperties(prefix = "app.persistence")
public record PersistenceProperties(
        boolean watchdogEnabled,
        long watchdogIntervalMs,
        int watchdogFailureThreshold) {

    public PersistenceProperties {
        watchdogIntervalMs = Math.max(1_000L, watchdogIntervalMs);
        watchdogFailureThreshold = Math.max(1, watchdogFailureThreshold);
    }
}
