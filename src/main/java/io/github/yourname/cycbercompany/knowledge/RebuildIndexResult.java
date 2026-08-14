package io.github.yourname.cycbercompany.knowledge;

public record RebuildIndexResult(
        String knowledgeBaseId,
        String documentId,
        int rebuiltDocumentCount,
        int chunkCount) {
}
