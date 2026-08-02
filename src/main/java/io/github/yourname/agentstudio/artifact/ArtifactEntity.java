package io.github.yourname.agentstudio.artifact;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "artifacts")
class ArtifactEntity {

    @Id
    private String id;
    @Column(nullable = false)
    private String tenantId;
    @Column(nullable = false)
    private String nodeId;
    private String runId;
    @Column(nullable = false)
    private String artifactType;
    @Column(nullable = false)
    private String filename;
    @Column(nullable = false)
    private String mimeType;
    @Column(nullable = false)
    private long sizeBytes;
    @Column(nullable = false, length = 80)
    private String digest;
    @Column(nullable = false, length = 1024)
    private String storagePath;
    @Column(nullable = false)
    private Instant createdAt;

    protected ArtifactEntity() {
    }

    ArtifactEntity(
            String id,
            String tenantId,
            String nodeId,
            String runId,
            String artifactType,
            String filename,
            String mimeType,
            long sizeBytes,
            String digest,
            String storagePath,
            Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.nodeId = nodeId;
        this.runId = runId;
        this.artifactType = artifactType;
        this.filename = filename;
        this.mimeType = mimeType;
        this.sizeBytes = sizeBytes;
        this.digest = digest;
        this.storagePath = storagePath;
        this.createdAt = createdAt;
    }

    String id() { return id; }
    String tenantId() { return tenantId; }
    String nodeId() { return nodeId; }
    String runId() { return runId; }
    String artifactType() { return artifactType; }
    String filename() { return filename; }
    String mimeType() { return mimeType; }
    long sizeBytes() { return sizeBytes; }
    String digest() { return digest; }
    String storagePath() { return storagePath; }
    Instant createdAt() { return createdAt; }
}
