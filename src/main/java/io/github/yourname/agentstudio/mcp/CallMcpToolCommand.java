package io.github.yourname.agentstudio.mcp;

import java.util.Map;

/**
 * Direct MCP tool invocation request.
 */
public record CallMcpToolCommand(Map<String, Object> arguments) {
}
