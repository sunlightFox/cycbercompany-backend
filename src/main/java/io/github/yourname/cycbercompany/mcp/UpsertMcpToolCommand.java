package io.github.yourname.cycbercompany.mcp;

import io.github.yourname.cycbercompany.tool.RiskLevel;
import jakarta.validation.constraints.NotBlank;

/**
 * Declares or replaces one MCP tool in a connection.
 */
public record UpsertMcpToolCommand(
        @NotBlank String name,
        String description,
        String inputSchema,
        RiskLevel riskLevel,
        Boolean requiresApproval,
        Boolean enabled) {
}
