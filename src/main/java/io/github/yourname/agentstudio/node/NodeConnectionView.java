package io.github.yourname.agentstudio.node;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

public record NodeConnectionView(
        String id,
        String name,
        String hostname,
        String osName,
        String osArch,
        String clientVersion,
        String capabilityRevision,
        Map<String, String> runtimeVersions,
        Set<String> features,
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
                entity.capabilityRevision(),
                entity.runtimeVersions(),
                entity.features(),
                entity.enabled(),
                entity.status(),
                entity.lastSeenAt(),
                entity.createdAt(),
                entity.updatedAt());
    }
}
