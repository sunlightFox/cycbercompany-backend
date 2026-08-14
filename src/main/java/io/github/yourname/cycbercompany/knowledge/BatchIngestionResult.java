package io.github.yourname.cycbercompany.knowledge;

import java.util.List;

/** Result of a batch document upload, including per-file failures. */
public record BatchIngestionResult(List<FileIngestionResult> files) {

    public BatchIngestionResult {
        files = List.copyOf(files);
    }

    public record FileIngestionResult(
            String sourceName,
            String documentId,
            int chunkCount,
            boolean duplicate,
            boolean succeeded,
            String error) {
    }
}
