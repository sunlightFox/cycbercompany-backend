package io.github.yourname.agentstudio.nodeclient.protocol;

import java.util.Map;

public record NodeCapability(
        String name,
        String description,
        String riskLevel,
        Boolean enabled,
        Boolean requiresApproval,
        Map<String, Object> inputSchema) {
}
