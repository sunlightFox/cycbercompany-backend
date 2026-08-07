package io.github.yourname.agentstudio.agent;

import java.time.Instant;
import java.util.Map;

public record AgentVersionView(
        String id,
        long revision,
        int versionNumber,
        int schemaVersion,
        AgentVersionState state,
        Map<String, Object> manifest,
        String manifestDigest,
        String compiledPromptDigest,
        String createdBy,
        Instant createdAt,
        Instant publishedAt) {
}
