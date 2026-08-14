package io.github.yourname.cycbercompany.mcp;

import io.github.yourname.cycbercompany.tool.RiskLevel;
import java.time.Instant;

/**
 * One tool exposed by a managed MCP connection.
 *
 * <p>{@code id} is globally unique inside this backend. {@code name} remains
 * the server-native MCP tool name so request payloads can be sent back to the
 * MCP server without guessing.
 */
public record McpToolView(
        String id,
        String name,
        String description,
        String inputSchema,
        RiskLevel riskLevel,
        boolean requiresApproval,
        boolean enabled,
        Instant discoveredAt) {
}
