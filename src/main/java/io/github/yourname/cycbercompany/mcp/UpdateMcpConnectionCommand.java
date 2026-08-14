package io.github.yourname.cycbercompany.mcp;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * Partial update for a managed MCP connection.
 *
 * <p>Null fields mean "leave unchanged"; non-null fields replace the stored
 * value. Tool updates are additive/upsert and preserve tools not mentioned.
 */
public record UpdateMcpConnectionCommand(
        String name,
        String description,
        McpTransportType transportType,
        Boolean enabled,
        String command,
        List<String> args,
        String endpoint,
        Map<String, String> env,
        Map<String, String> metadata,
        @Valid List<UpsertMcpToolCommand> tools) {
}
