package io.github.yourname.cycbercompany.mcp;

import jakarta.validation.constraints.NotBlank;

/**
 * Imports one or more MCP connections from raw JSON pasted by an administrator.
 */
public record ImportMcpConnectionsCommand(
        @NotBlank String json,
        Boolean overwrite,
        Boolean enabled,
        Boolean refreshTools) {
}
