package io.github.yourname.cycbercompany.mcp;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Full MCP connection configuration visible to administrators.
 *
 * <p>Sensitive environment variable values are never returned. Only variable
 * names are listed so reviewers can understand what the server expects.
 */
public record McpConnectionView(
        String id,
        String name,
        String description,
        McpTransportType transportType,
        boolean enabled,
        McpConnectionStatus status,
        String command,
        List<String> args,
        String endpoint,
        List<String> envKeys,
        Map<String, String> metadata,
        List<McpToolView> tools,
        Instant createdAt,
        Instant updatedAt,
        String lastError) {
}
