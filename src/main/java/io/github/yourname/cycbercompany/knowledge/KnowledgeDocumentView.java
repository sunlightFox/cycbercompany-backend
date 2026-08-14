package io.github.yourname.cycbercompany.knowledge;

import java.time.Instant;

public record KnowledgeDocumentView(
        String id,
        String knowledgeBaseId,
        String sourceName,
        String contentHash,
        String contentType,
        long contentLength,
        int chunkCount,
        String summary,
        boolean rebuildable,
        Instant createdAt,
        Instant updatedAt) {

    static KnowledgeDocumentView from(KnowledgeDocumentEntity entity) {
        return new KnowledgeDocumentView(
                entity.id(),
                entity.knowledgeBaseId(),
                entity.sourceName(),
                entity.contentHash(),
                entity.contentType(),
                entity.contentLength(),
                entity.chunkCount(),
                entity.summary(),
                entity.extractedText() != null && !entity.extractedText().isBlank(),
                entity.createdAt(),
                entity.updatedAt());
    }
}
