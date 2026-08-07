package io.github.yourname.agentstudio.conversation;

import java.time.Instant;
import java.util.List;

/** 返回给前端的会话及消息历史视图。 */
public record ConversationView(
        String id,
        String title,
        Instant createdAt,
        boolean archived,
        Instant archivedAt,
        String personaId,
        List<MessageView> messages) {

    public static ConversationView from(ConversationEntity entity, List<MessageView> messages) {
        return new ConversationView(
                entity.id(),
                entity.title(),
                entity.createdAt(),
                entity.archived(),
                entity.archivedAt(),
                entity.userPersonaId(),
                messages);
    }
}
