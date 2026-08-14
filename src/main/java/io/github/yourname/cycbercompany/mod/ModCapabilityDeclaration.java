package io.github.yourname.cycbercompany.mod;

import java.util.Map;

public record ModCapabilityDeclaration(
        String id,
        String description,
        String execution,
        boolean requiresAgentPlanning,
        Map<String, Object> inputSchema) {
}
