package io.github.yourname.agentstudio.conversation;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.time.Instant;

/** 聊天会话的持久化实体。 */
@Entity(name = "conversation")
public class ConversationEntity {

    @Id
    /** 会话 ID，也是创建 Run 时传入的 conversationId。 */
    private String id;
    /** 数据归属租户。 */
    private String tenantId;
    /** 会话标题。 */
    private String title;
    /** 会话创建时间。 */
    private Instant createdAt;
    /** 归档时间；为空表示仍可写入。 */
    private Instant archivedAt;

    protected ConversationEntity() {
    }

    public ConversationEntity(String id, String tenantId, String title, Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.title = title;
        this.createdAt = createdAt;
    }

    public void archive(Instant now) {
        if (archivedAt == null) {
            archivedAt = now;
        }
    }

    public String id() { return id; }
    public String tenantId() { return tenantId; }
    public String title() { return title; }
    public Instant createdAt() { return createdAt; }
    public boolean archived() { return archivedAt != null; }
    public Instant archivedAt() { return archivedAt; }
}
