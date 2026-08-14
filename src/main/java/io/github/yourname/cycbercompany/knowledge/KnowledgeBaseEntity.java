package io.github.yourname.cycbercompany.knowledge;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.time.Instant;

/** 租户范围内的一组知识文档。 */
@Entity(name = "knowledge_base")
public class KnowledgeBaseEntity {

    @Id
    /** 知识库 ID。 */
    private String id;
    /** 数据归属租户，所有查询都必须使用它过滤。 */
    private String tenantId;
    /** 知识库名称。 */
    private String name;
    /** 知识库说明。 */
    private String description;
    /** 创建时间。 */
    private Instant createdAt;
    /** 最近一次修改展示信息的时间。 */
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
