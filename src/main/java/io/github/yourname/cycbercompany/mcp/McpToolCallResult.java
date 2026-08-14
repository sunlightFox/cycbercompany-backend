package io.github.yourname.cycbercompany.mcp;

import java.util.List;
import java.util.Map;

/**
 * Normalized result returned after calling an MCP tool.
 */
public record McpToolCallResult(
        String connectionId,
        String toolName,
        boolean error,
        String text,
        List<Map<String, Object>> content,
        Object rawResult) {
}
