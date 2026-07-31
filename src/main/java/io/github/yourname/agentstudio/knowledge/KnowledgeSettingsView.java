package io.github.yourname.agentstudio.knowledge;

/**
 * 知识库/RAG 运行时配置视图。
 *
 * <p>只暴露非敏感配置，不返回任何 API Key。
 */
public record KnowledgeSettingsView(
        boolean embeddingEnabled,
        String embeddingModelProfileId,
        int maxEmbeddingChars,
        double vectorWeight) {
}
