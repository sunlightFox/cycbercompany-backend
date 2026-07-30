package io.github.yourname.agentstudio.knowledge;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.time.Instant;

@Entity(name = "knowledge_base")
public class KnowledgeBaseEntity {

    @Id
    private String id;
    private String tenantId;
    private String name;
    private String description;
    private Instant createdAt;

    protected KnowledgeBaseEntity() {
    }

    public KnowledgeBaseEntity(String id, String tenantId, String name, String description, Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.name = name;
        this.description = description;
        this.createdAt = createdAt;
    }

    public String id() { return id; }
    public String tenantId() { return tenantId; }
    public String name() { return name; }
    public String description() { return description; }
    public Instant createdAt() { return createdAt; }
}
