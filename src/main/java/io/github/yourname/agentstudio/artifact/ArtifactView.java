package io.github.yourname.agentstudio.artifact;

import java.time.Instant;

/** 可放进 WebSocket/SSE 的小型 Artifact 引用，不包含服务端真实存储路径。 */
public record ArtifactView(
        String id,
        String runId,
        String artifactType,
        String filename,
        String mimeType,
        long sizeBytes,
        String digest,
        String downloadUrl,
        Instant createdAt) {

    static ArtifactView from(ArtifactEntity entity) {
        return new ArtifactView(
                entity.id(), entity.runId(), entity.artifactType(), entity.filename(), entity.mimeType(),
                entity.sizeBytes(), entity.digest(), "/api/v1/artifacts/" + entity.id(), entity.createdAt());
    }
}
