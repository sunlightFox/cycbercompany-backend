package io.github.yourname.agentstudio.mcp;

/**
 * Transport choices supported by the management layer.
 *
 * <p>STDIO is the common local MCP server mode. STREAMABLE_HTTP is the modern
 * remote HTTP mode. SSE is retained because many older MCP servers still expose
 * it and users will find such examples in public repositories.
 */
public enum McpTransportType {
    STDIO,
    STREAMABLE_HTTP,
    SSE
}
