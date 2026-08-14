package io.github.yourname.cycbercompany.media;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity(name = "media_progress")
@Table(
        uniqueConstraints = @UniqueConstraint(
                name = "uk_media_progress_owner_mod_media",
                columnNames = {"tenant_id", "user_id", "mod_id", "media_id"}),
        indexes = @Index(name = "ix_media_progress_owner_updated", columnList = "tenant_id,user_id,updated_at"))
public class MediaProgressEntity {

    @Id
    private String id;
    private String tenantId;
    private String userId;
    private String modId;
    private String mediaId;
    private String sourceId;
    private String episodeId;
    private long positionMs;
    private long durationMs;
    private boolean completed;
    private Instant updatedAt;
    @Version
    private long revision;

    protected MediaProgressEntity() {
    }

    public MediaProgressEntity(String id, String tenantId, String userId, String modId, String mediaId,
                               String sourceId, String episodeId, long positionMs, long durationMs,
                               boolean completed, Instant updatedAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.userId = userId;
        this.modId = modId;
        this.mediaId = mediaId;
        this.sourceId = sourceId;
        this.episodeId = episodeId;
        this.positionMs = positionMs;
        this.durationMs = durationMs;
        this.completed = completed;
        this.updatedAt = updatedAt;
    }

    public void update(String sourceId, String episodeId, long positionMs, long durationMs,
                       boolean completed, Instant now) {
        this.sourceId = sourceId;
        this.episodeId = episodeId;
        this.positionMs = positionMs;
        this.durationMs = durationMs;
        this.completed = completed;
        this.updatedAt = now;
    }

    public String id() { return id; }
    public String tenantId() { return tenantId; }
    public String userId() { return userId; }
    public String modId() { return modId; }
    public String mediaId() { return mediaId; }
    public String sourceId() { return sourceId; }
    public String episodeId() { return episodeId; }
    public long positionMs() { return positionMs; }
    public long durationMs() { return durationMs; }
    public boolean completed() { return completed; }
    public Instant updatedAt() { return updatedAt; }
    public long revision() { return revision; }
}
