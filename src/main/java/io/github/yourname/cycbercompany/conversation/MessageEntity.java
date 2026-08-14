package io.github.yourname.cycbercompany.conversation;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import java.time.Instant;

/**
 * 会话中的一条消息。
 *
 * <p>{@code runId} 允许把用户消息或助手消息关联到一次执行；普通历史展示不需要依赖
 * Run 事件表，因此消息和事件是两个独立的数据模型。
 */
@Entity(name = "message")
public class MessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    /** 消息数据库主键。 */
    private Long id;
    /** 租户 ID。 */
    private String tenantId;
    /** 所属会话。 */
    private String conversationId;

    @Enumerated(EnumType.STRING)
    /** 消息角色：用户、助手或系统。 */
    private MessageRole role;

    @Lob
    /** 消息正文。 */
    private String content;
    /** 触发或生成这条消息的 Run，可为空。 */
    private String runId;
    /** 消息创建时间，用于恢复模型上下文顺序。 */
    private Instant createdAt;

    protected MessageEntity() {
    }

    public MessageEntity(String tenantId, String conversationId, MessageRole role, String content, String runId, Instant createdAt) {
        this.tenantId = tenantId;
        this.conversationId = conversationId;
        this.role = role;
        this.content = content;
        this.runId = runId;
        this.createdAt = createdAt;
    }

    public Long id() { return id; }
    public String tenantId() { return tenantId; }
    public String conversationId() { return conversationId; }
    public MessageRole role() { return role; }
    public String content() { return content; }
    public String runId() { return runId; }
    public Instant createdAt() { return createdAt; }
}
