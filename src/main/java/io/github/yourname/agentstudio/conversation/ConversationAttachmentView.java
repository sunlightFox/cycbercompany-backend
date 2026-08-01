package io.github.yourname.agentstudio.conversation;

import java.time.Instant;

public record ConversationAttachmentView(
        String id,
        String fileName,
        String contentType,
        long byteSize,
        Instant createdAt) {

    static ConversationAttachmentView from(ConversationAttachmentEntity entity) {
        return new ConversationAttachmentView(
                entity.id(), entity.fileName(), entity.contentType(), entity.byteSize(), entity.createdAt());
    }
}
