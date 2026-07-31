package io.github.yourname.agentstudio.knowledge;

import java.time.Instant;

public record KnowledgeBaseView(
        String id,
        String name,
        String description,
        long documentCount,
        long chunkCount,
        Instant createdAt,
        Instant updatedAt) {
    static KnowledgeBaseView from(KnowledgeBaseEntity entity) {
        return from(entity, 0, 0);
    }

    static KnowledgeBaseView from(KnowledgeBaseEntity entity, long documentCount, long chunkCount) {
        return new KnowledgeBaseView(
                entity.id(),
                entity.name(),
                entity.description(),
                documentCount,
                chunkCount,
                entity.createdAt(),
                entity.updatedAt());
    }
}
