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
    private Instant updatedAt;

    protected KnowledgeBaseEntity() {
    }

    public KnowledgeBaseEntity(String id, String tenantId, String name, String description, Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.name = name;
        this.description = description;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public String id() { return id; }
    public String tenantId() { return tenantId; }
    public String name() { return name; }
    public String description() { return description; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }

    /**
     * 更新知识库的展示信息。
     *
     * <p>这里不允许修改 tenantId/id，因为它们是权限隔离和数据归属的根。
     */
    public void update(String name, String description) {
        this.name = name;
        this.description = description;
        this.updatedAt = Instant.now();
    }
}
