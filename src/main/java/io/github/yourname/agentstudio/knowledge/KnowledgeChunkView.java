package io.github.yourname.agentstudio.knowledge;

public record KnowledgeChunkView(
        Long id,
        String documentId,
        String knowledgeBaseId,
        String sourceName,
        int chunkIndex,
        String content,
        boolean embeddingIndexed) {

    static KnowledgeChunkView from(KnowledgeChunkEntity entity) {
        return new KnowledgeChunkView(
                entity.id(),
                entity.documentId(),
                entity.knowledgeBaseId(),
                entity.sourceName(),
                entity.chunkIndex(),
                entity.content(),
                entity.embeddingVector() != null && !entity.embeddingVector().isBlank());
    }
}
