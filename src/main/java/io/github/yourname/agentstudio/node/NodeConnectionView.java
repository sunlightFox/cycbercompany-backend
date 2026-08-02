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
        NodeKind kind,
        String capabilityRevision,
        Map<String, String> runtimeVersions,
        Set<String> features,
        Set<String> labels,
        boolean enabled,
        NodeStatus status,
        Instant lastSeenAt,
        Instant createdAt,
        Instant updatedAt) {

    /** Compatibility constructor for callers compiled before sandbox scheduling labels existed. */
    public NodeConnectionView(
            String id,
            String name,
            String hostname,
            String osName,
            String osArch,
            String clientVersion,
            NodeKind kind,
            String capabilityRevision,
            Map<String, String> runtimeVersions,
            Set<String> features,
            boolean enabled,
            NodeStatus status,
            Instant lastSeenAt,
            Instant createdAt,
            Instant updatedAt) {
        this(
                id,
                name,
                hostname,
                osName,
                osArch,
                clientVersion,
                kind,
                capabilityRevision,
                runtimeVersions,
                features,
                Set.of(),
                enabled,
                status,
                lastSeenAt,
                createdAt,
                updatedAt);
    }

    public static NodeConnectionView from(NodeConnectionEntity entity) {
        return new NodeConnectionView(
                entity.id(),
                entity.name(),
                entity.hostname(),
                entity.osName(),
                entity.osArch(),
                entity.clientVersion(),
                entity.kind(),
                entity.capabilityRevision(),
                entity.runtimeVersions(),
                entity.features(),
                entity.labels(),
                entity.enabled(),
                entity.status(),
                entity.lastSeenAt(),
                entity.createdAt(),
                entity.updatedAt());
    }
}
