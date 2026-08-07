package io.github.yourname.agentstudio.conversation;

import java.time.Instant;

/** Compact conversation data for history navigation and search results. */
public record ConversationSummaryView(
        String id,
        String title,
        Instant createdAt,
        Instant lastActivityAt,
        boolean archived,
        String lastMessagePreview) {

    static ConversationSummaryView from(ConversationEntity entity, String lastMessagePreview) {
        return new ConversationSummaryView(
                entity.id(), entity.title(), entity.createdAt(), entity.lastActivityAt(), entity.archived(), lastMessagePreview);
    }
}
