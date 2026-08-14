package io.github.yourname.cycbercompany.knowledge;

/**
 * 知识库/RAG 运行时配置视图。
 *
 * <p>只暴露非敏感配置，不返回任何 API Key。
 */
public record KnowledgeSettingsView(
        boolean embeddingEnabled,
        boolean embeddingCredentialConfigured,
        String embeddingModel,
        String embeddingBaseUrl,
        String embeddingCredentialEnv,
        String vectorStore,
        int chunkSize,
        int chunkOverlap) {
}
