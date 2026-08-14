package io.github.yourname.cycbercompany.mcp;

import java.util.Map;

/** Installs a remote MCP entry returned by MCPMarket in one request. */
public record InstallMcpRepositoryCommand(
        String id,
        String repositoryId,
        String name,
        String description,
        String endpoint,
        McpTransportType transportType,
        Map<String, String> env,
        Boolean enabled,
        Boolean refreshTools) {
}
