package io.github.yourname.agentstudio.node;

import java.time.Instant;

public record NodeConnectionView(
        String id,
        String name,
        String hostname,
        String osName,
        String osArch,
        String clientVersion,
        boolean enabled,
        NodeStatus status,
        Instant lastSeenAt,
        Instant createdAt,
        Instant updatedAt) {

    public static NodeConnectionView from(NodeConnectionEntity entity) {
        return new NodeConnectionView(
                entity.id(),
                entity.name(),
                entity.hostname(),
                entity.osName(),
                entity.osArch(),
                entity.clientVersion(),
                entity.enabled(),
                entity.status(),
                entity.lastSeenAt(),
                entity.createdAt(),
                entity.updatedAt());
    }
}
