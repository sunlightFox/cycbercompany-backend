package io.github.yourname.agentstudio.node;

import io.github.yourname.agentstudio.tool.RiskLevel;
import java.util.Map;

public record NodeCapabilityPayload(
        String name,
        String description,
        RiskLevel riskLevel,
        Boolean enabled,
        Boolean requiresApproval,
        Map<String, Object> inputSchema) {
}
