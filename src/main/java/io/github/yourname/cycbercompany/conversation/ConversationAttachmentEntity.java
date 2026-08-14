package io.github.yourname.cycbercompany.conversation;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.time.Instant;

@Entity(name = "conversation_attachment")
public class ConversationAttachmentEntity {

    @Id
    private String id;
    private String tenantId;
    private String conversationId;
    private String fileName;
    private String contentType;
    private long byteSize;
    private String storageKey;
    private Instant createdAt;

    protected ConversationAttachmentEntity() {
    }

    public ConversationAttachmentEntity(
            String id,
            String tenantId,
            String conversationId,
            String fileName,
            String contentType,
            long byteSize,
            String storageKey,
            Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.conversationId = conversationId;
        this.fileName = fileName;
        this.contentType = contentType;
        this.byteSize = byteSize;
        this.storageKey = storageKey;
        this.createdAt = createdAt;
    }

    public String id() { return id; }
    public String tenantId() { return tenantId; }
    public String conversationId() { return conversationId; }
    public String fileName() { return fileName; }
    public String contentType() { return contentType; }
    public long byteSize() { return byteSize; }
    public String storageKey() { return storageKey; }
    public Instant createdAt() { return createdAt; }
}
