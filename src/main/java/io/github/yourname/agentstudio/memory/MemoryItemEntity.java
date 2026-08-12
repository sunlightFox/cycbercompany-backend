package io.github.yourname.agentstudio.memory;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Column;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity(name = "memory_item")
@Table(indexes = {
        @Index(name = "ix_memory_owner_updated", columnList = "tenant_id,user_id,updated_at"),
        @Index(name = "ix_memory_agent_status", columnList = "tenant_id,agent_id,status")
})
public class MemoryItemEntity {

    @Id
    private String id;
    private String tenantId;
    private String userId;
    private String agentId;
    private String scope;
    private String origin;
    private String memoryKey;
    private String supersededBy;
    private String personaId;
    private String type;
    private String status;
    private String sensitivity;
    @Column(length = 4000)
    private String content;
    private double confidence;
    private double importance;
    private String sourceConversationId;
    private String sourceRunId;
    @Lob
    private String evidenceSummary;
    @Lob
    private String embeddingVector;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant lastUsedAt;
    private Instant expiresAt;
    @Version
    private long revision;

    protected MemoryItemEntity() {
    }

    public MemoryItemEntity(
            String id,
            String tenantId,
            String userId,
            String agentId,
            MemoryScope scope,
            MemoryOrigin origin,
            String memoryKey,
            String personaId,
            MemoryType type,
            MemoryStatus status,
            MemorySensitivity sensitivity,
            String content,
            double confidence,
            double importance,
            String sourceConversationId,
            String sourceRunId,
            String evidenceSummary,
            String embeddingVector,
            Instant now,
            Instant expiresAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.userId = userId;
        this.agentId = agentId;
        this.scope = scope.name();
        this.origin = origin.name();
        this.memoryKey = memoryKey;
        this.personaId = personaId;
        this.type = type.name();
        this.status = status.name();
        this.sensitivity = sensitivity.name();
        this.content = content;
        this.confidence = confidence;
        this.importance = importance;
        this.sourceConversationId = sourceConversationId;
        this.sourceRunId = sourceRunId;
        this.evidenceSummary = evidenceSummary;
        this.embeddingVector = embeddingVector;
        this.createdAt = now;
        this.updatedAt = now;
        this.expiresAt = expiresAt;
    }

    public MemoryItemEntity(
            String id, String tenantId, String userId, String agentId, MemoryScope scope, String personaId,
            MemoryType type, MemoryStatus status, MemorySensitivity sensitivity, String content,
            double confidence, double importance, String sourceConversationId, String sourceRunId,
            String evidenceSummary, String embeddingVector, Instant now, Instant expiresAt) {
        this(id, tenantId, userId, agentId, scope, MemoryOrigin.USER_CREATED, null, personaId, type, status,
                sensitivity, content, confidence, importance, sourceConversationId, sourceRunId,
                evidenceSummary, embeddingVector, now, expiresAt);
    }

    public void revise(
            MemoryType type,
            String content,
            double importance,
            Instant expiresAt,
            String embeddingVector,
            Instant now) {
        this.type = type.name();
        this.content = content;
        this.importance = importance;
        this.expiresAt = expiresAt;
        this.embeddingVector = embeddingVector;
        this.updatedAt = now;
    }

    public void changeScope(MemoryScope scope, String memoryKey, String personaId, Instant now) {
        this.scope = scope.name();
        this.memoryKey = memoryKey;
        this.personaId = personaId;
        this.updatedAt = now;
    }

    public void confirm(Instant now) {
        status = MemoryStatus.CONFIRMED.name();
        updatedAt = now;
    }

    public void reject(Instant now) {
        status = MemoryStatus.REJECTED.name();
        updatedAt = now;
    }

    public String id() { return id; }
    public String tenantId() { return tenantId; }
    public String userId() { return userId; }
    public String agentId() { return agentId; }
    public MemoryScope scope() { return scope == null || scope.isBlank() ? MemoryScope.USER : MemoryScope.valueOf(scope); }
    public MemoryOrigin origin() { return origin == null || origin.isBlank() ? MemoryOrigin.USER_CREATED : MemoryOrigin.valueOf(origin); }
    public String memoryKey() { return memoryKey; }
    public String supersededBy() { return supersededBy; }

    public void supersede(String replacementId, Instant now) {
        this.supersededBy = replacementId;
        this.updatedAt = now;
    }
    public String personaId() { return personaId; }
    public MemoryType type() { return MemoryType.valueOf(type); }
    public MemoryStatus status() { return MemoryStatus.valueOf(status); }
    public MemorySensitivity sensitivity() { return MemorySensitivity.valueOf(sensitivity); }
    public String content() { return content; }
    public double confidence() { return confidence; }
    public double importance() { return importance; }
    public String sourceConversationId() { return sourceConversationId; }
    public String sourceRunId() { return sourceRunId; }
    public String evidenceSummary() { return evidenceSummary; }
    public String embeddingVector() { return embeddingVector; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public Instant lastUsedAt() { return lastUsedAt; }
    public Instant expiresAt() { return expiresAt; }
    public long revision() { return revision; }
}
