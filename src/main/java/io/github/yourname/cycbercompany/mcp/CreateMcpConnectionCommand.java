package io.github.yourname.cycbercompany.mcp;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;

/**
 * Creates a managed MCP connection.
 *
 * <p>For this version, tools are declared or imported as metadata. The backend
 * intentionally does not auto-run arbitrary STDIO commands during creation.
 * Environment entries may reference the backend process environment with the
 * {@code env:VARIABLE_NAME} form; this keeps credentials out of saved MCP JSON.
 */
public record CreateMcpConnectionCommand(
        String id,
        @NotBlank String name,
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
