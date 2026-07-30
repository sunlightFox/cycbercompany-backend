package io.github.yourname.agentstudio.conversation;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.time.Instant;

@Entity(name = "conversation")
public class ConversationEntity {

    @Id
    private String id;
    private String tenantId;
    private String title;
    private Instant createdAt;

    protected ConversationEntity() {
    }

    public ConversationEntity(String id, String tenantId, String title, Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.title = title;
        this.createdAt = createdAt;
    }

    public String id() { return id; }
    public String tenantId() { return tenantId; }
    public String title() { return title; }
    public Instant createdAt() { return createdAt; }
}
