package io.github.yourname.cycbercompany.node;

import io.github.yourname.cycbercompany.tool.RiskLevel;
import java.time.Instant;

public record NodeToolView(
        Long id,
        String nodeId,
        String name,
        String capabilityVersion,
        String description,
        RiskLevel riskLevel,
        boolean enabled,
        boolean requiresApproval,
        String inputSchemaJson,
        Instant createdAt,
        Instant updatedAt) {

    public static NodeToolView from(NodeToolEntity entity) {
        return new NodeToolView(
                entity.id(),
                entity.nodeId(),
                entity.name(),
                entity.capabilityVersion(),
                entity.description(),
                entity.riskLevel(),
                entity.enabled(),
                entity.requiresApproval(),
                entity.inputSchemaJson(),
                entity.createdAt(),
                entity.updatedAt());
    }
}
