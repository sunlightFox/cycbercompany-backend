package io.github.yourname.agentstudio.mcp;

/**
 * Public repository or curated source where MCP servers can be found.
 */
public record McpRepositoryView(
        String id,
        String name,
        String description,
        String url,
        String defaultBranch,
        int stars,
        String sourceType) {
}
