package io.github.yourname.agentstudio.config;

import io.github.yourname.agentstudio.model.ModelCapability;
import io.github.yourname.agentstudio.model.ProviderType;
import java.nio.file.Path;
import java.util.EnumSet;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Central application settings.
 *
 * <p>The model credential is deliberately represented by an environment-variable
 * name instead of a raw secret. This lets the repository teach the full wiring
 * without turning configuration files into a credential leak surface.
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(Path dataDir, Ai ai, Run run, WebSearch webSearch, SkillStore skills, McpStore mcp) {

    public record Run(long timeoutSeconds) {
    }

    public record WebSearch(boolean enabled, int maxResults, String endpoint) {
    }

    public record SkillStore(Path installDir, int maxArchiveBytes, int maxFiles, int maxFileBytes) {
    }

    public record McpStore(Path configDir) {
    }

    public record Ai(
            String defaultModelProfileId,
            boolean seedDefaultProfile,
            DefaultModelProfile defaultProfile) {
    }

    public record DefaultModelProfile(
            String id,
            ProviderType providerType,
            String baseUrl,
            String modelName,
            String credentialRef,
            EnumSet<ModelCapability> capabilities) {
    }
}
