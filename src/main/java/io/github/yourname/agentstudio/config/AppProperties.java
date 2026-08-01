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

    public record WebSearch(
            boolean enabled,
            int maxResults,
            String endpoint,
            int perDomainLimit,
            int minUniqueDomains,
            String language,
            int safeSearch,
            PageReader pageReader,
            NewsSources newsSources,
            SearchPlanning planning,
            String generalEngines,
            String newsEngines) {

        public WebSearch(
                boolean enabled,
                int maxResults,
                String endpoint,
                int perDomainLimit,
                int minUniqueDomains,
                String language,
                int safeSearch,
                PageReader pageReader,
                NewsSources newsSources,
                SearchPlanning planning) {
            this(enabled, maxResults, endpoint, perDomainLimit, minUniqueDomains, language, safeSearch,
                    pageReader, newsSources, planning, "mwmbl,bing,baidu", "bing news,reuters");
        }

        public WebSearch(
                boolean enabled,
                int maxResults,
                String endpoint,
                int perDomainLimit,
                int minUniqueDomains,
                String language,
                int safeSearch,
                PageReader pageReader,
                NewsSources newsSources) {
            this(enabled, maxResults, endpoint, perDomainLimit, minUniqueDomains, language, safeSearch,
                    pageReader, newsSources, SearchPlanning.defaults());
        }

        public WebSearch(
                boolean enabled,
                int maxResults,
                String endpoint,
                int perDomainLimit,
                int minUniqueDomains,
                String language,
                int safeSearch,
                PageReader pageReader) {
            this(enabled, maxResults, endpoint, perDomainLimit, minUniqueDomains, language, safeSearch,
                    pageReader, NewsSources.defaults(), SearchPlanning.defaults());
        }

        /** Retains the original three-setting form for programmatic callers. */
        public WebSearch(boolean enabled, int maxResults, String endpoint) {
            this(enabled, maxResults, endpoint, 2, 3, "zh-CN", 1, PageReader.defaults());
        }
    }

    /** Bounded query fan-out and provider-result caching for broad or current searches. */
    public record SearchPlanning(int maxQueries, long cacheTtlSeconds) {

        public static SearchPlanning defaults() {
            return new SearchPlanning(3, 90);
        }
    }

    /** Optional free sources used to improve current-news coverage and page extraction. */
    public record NewsSources(
            boolean gdeltEnabled,
            String gdeltEndpoint,
            boolean jinaReaderEnabled,
            String jinaReaderEndpoint) {

        public static NewsSources defaults() {
            return new NewsSources(
                    true,
                    "https://api.gdeltproject.org/api/v2/doc/doc",
                    true,
                    "https://r.jina.ai/");
        }
    }

    /** Limits HTML retrieval performed after a search result has been selected. */
    public record PageReader(
            boolean enabled,
            int maxResults,
            int maxExcerptChars,
            int maxResponseBytes,
            boolean allowPrivateHosts) {

        public static PageReader defaults() {
            return new PageReader(true, 8, 1_500, 1_000_000, false);
        }
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
