package io.github.yourname.agentstudio.agent;

import java.time.Instant;
import java.util.List;

public record AgentV2View(
        String id,
        String displayName,
        String description,
        String avatarRef,
        String category,
        List<String> tags,
        String visibility,
        String status,
        String currentPublishedVersionId,
        long revision,
        Instant createdAt,
        Instant updatedAt,
        AgentVersionView currentPublishedVersion,
        AgentVersionView latestDraft) {

    public AgentV2View {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
