package io.github.yourname.cycbercompany.knowledge;

import java.util.List;

public record EvidenceBundle(List<Evidence> evidence) {

    public boolean isEmpty() {
        return evidence == null || evidence.isEmpty();
    }

    public record Evidence(
            Long chunkId,
            String documentId,
            String knowledgeBaseId,
            String sourceName,
            int chunkIndex,
            String quote,
            double score) {
    }
}
