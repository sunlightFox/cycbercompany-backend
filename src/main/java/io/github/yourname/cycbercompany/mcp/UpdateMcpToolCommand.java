package io.github.yourname.cycbercompany.mcp;

import io.github.yourname.cycbercompany.tool.RiskLevel;

/**
 * Partial update for a single MCP tool.
 */
public record UpdateMcpToolCommand(
        String description,
        String inputSchema,
        RiskLevel riskLevel,
        Boolean requiresApproval,
        Boolean enabled) {
}
