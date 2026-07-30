package io.github.yourname.agentstudio.mcp;

import io.github.yourname.agentstudio.tool.RiskLevel;

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
