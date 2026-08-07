package io.github.yourname.agentstudio.agent;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity(name = "agent_identity")
public class AgentIdentityEntity {

    @Id
    private String id;
    private String tenantId;
    private String ownerUserId;
    private String displayName;
    private String description;
    private String avatarRef;
    private String category;
    @Lob
    private String tagsJson;
    private String visibility;
    private String status;
    private String currentPublishedVersionId;
    private Instant createdAt;
    private Instant updatedAt;
    @Version
    private long revision;

    protected AgentIdentityEntity() {
    }

    public AgentIdentityEntity(
            String id,
            String tenantId,
            String ownerUserId,
            String displayName,
            String description,
            String avatarRef,
            String category,
            String tagsJson,
            String visibility,
            Instant now) {
        this.id = id;
        this.tenantId = tenantId;
        this.ownerUserId = ownerUserId;
        this.displayName = displayName;
        this.description = description;
        this.avatarRef = avatarRef;
        this.category = category;
        this.tagsJson = tagsJson;
        this.visibility = visibility;
        this.status = "ACTIVE";
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void publish(String versionId, Instant now) {
        currentPublishedVersionId = versionId;
        updatedAt = now;
    }

    public void update(String displayName, String description, String avatarRef, String category, String tagsJson, String visibility, Instant now) {
        this.displayName = displayName;
        this.description = description;
        this.avatarRef = avatarRef;
        this.category = category;
        this.tagsJson = tagsJson;
        this.visibility = visibility;
        this.updatedAt = now;
    }

    public void archive(Instant now) {
        status = "ARCHIVED";
        updatedAt = now;
    }

    public void updateSettings(String visibility, String status, Instant now) {
        this.visibility = visibility;
        this.status = status;
        this.updatedAt = now;
    }

    public String id() { return id; }
    public String tenantId() { return tenantId; }
    public String ownerUserId() { return ownerUserId; }
    public String displayName() { return displayName; }
    public String description() { return description; }
    public String avatarRef() { return avatarRef; }
    public String category() { return category; }
    public String tagsJson() { return tagsJson; }
    public String visibility() { return visibility; }
    public String status() { return status; }
    public String currentPublishedVersionId() { return currentPublishedVersionId; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public long revision() { return revision; }
}
