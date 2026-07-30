package io.github.yourname.agentstudio.knowledge;

import java.time.Instant;

public record KnowledgeBaseView(String id, String name, String description, Instant createdAt) {
    static KnowledgeBaseView from(KnowledgeBaseEntity entity) {
        return new KnowledgeBaseView(entity.id(), entity.name(), entity.description(), entity.createdAt());
    }
}
