package io.github.yourname.cycbercompany.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the executor embedded in a personal-local backend process. */
@ConfigurationProperties(prefix = "app.local-executor")
public record LocalExecutorProperties(boolean enabled, Path workspace) {
}
