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
public record AppProperties(
        Path dataDir,
        Ai ai,
        Run run,
        WebSearch webSearch,
        SkillStore skills,
        McpStore mcp,
        Rag rag) {

    public record Run(long timeoutSeconds) {
    }

    public record WebSearch(boolean enabled, int maxResults, String endpoint) {
    }

    public record SkillStore(Path installDir, int maxArchiveBytes, int maxFiles, int maxFileBytes) {
    }

    public record McpStore(Path configDir) {
    }

    /**
     * RAG/知识库配置。
     *
     * <p>embedding 默认关闭，是为了让项目在没有向量模型 Key 的情况下也能完整启动和测试；
     * 配好 embedding 模型后，只要打开开关，摄取新文档和重建索引就会自动生成 chunk 向量。
     */
    public record Rag(
            boolean embeddingEnabled,
            String embeddingModelProfileId,
            int maxEmbeddingChars,
            double vectorWeight) {
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
