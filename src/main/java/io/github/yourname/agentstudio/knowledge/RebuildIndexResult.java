package io.github.yourname.agentstudio.knowledge;

public record RebuildIndexResult(
        String knowledgeBaseId,
        String documentId,
        int rebuiltDocumentCount,
        int chunkCount) {
}
