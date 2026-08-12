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
    /** Temporary local-demo owner. Replaced by the authenticated user ID later. */
    private String userId;
    /** 会话标题。 */
    private String title;
    /** 会话创建时间。 */
    private Instant createdAt;
    private Instant lastActivityAt;
    /** 归档时间；为空表示仍可写入。 */
    private Instant archivedAt;
    private String userPersonaId;

    protected ConversationEntity() {
    }

    public ConversationEntity(String id, String tenantId, String title, Instant createdAt) {
        this(id, tenantId, null, title, createdAt);
    }

    public ConversationEntity(String id, String tenantId, String userId, String title, Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.userId = userId;
        this.title = title;
        this.createdAt = createdAt;
        this.lastActivityAt = createdAt;
    }

    public void archive(Instant now) {
        if (archivedAt == null) {
            archivedAt = now;
        }
    }

    public void recordActivity(Instant now) {
        lastActivityAt = now;
    }

    public void rename(String title) {
        this.title = title;
    }

    public void selectPersona(String personaId) {
        this.userPersonaId = personaId;
    }

    public String id() { return id; }
    public String tenantId() { return tenantId; }
    public String userId() { return userId; }
    public String title() { return title; }
    public Instant createdAt() { return createdAt; }
    public Instant lastActivityAt() { return lastActivityAt == null ? createdAt : lastActivityAt; }
    public boolean archived() { return archivedAt != null; }
    public Instant archivedAt() { return archivedAt; }
    public String userPersonaId() { return userPersonaId; }
}
