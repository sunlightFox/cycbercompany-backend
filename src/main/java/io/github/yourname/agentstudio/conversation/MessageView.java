package io.github.yourname.agentstudio.conversation;

import java.time.Instant;

public record MessageView(Long id, MessageRole role, String content, String runId, Instant createdAt) {

    public static MessageView from(MessageEntity entity) {
        return new MessageView(entity.id(), entity.role(), entity.content(), entity.runId(), entity.createdAt());
    }
}
