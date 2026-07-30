package io.github.yourname.agentstudio.conversation;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import java.time.Instant;

@Entity(name = "message")
public class MessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String tenantId;
    private String conversationId;

    @Enumerated(EnumType.STRING)
    private MessageRole role;

    @Lob
    private String content;
    private String runId;
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
