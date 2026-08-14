package io.github.yourname.cycbercompany.mcp;

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
        String sourceType,
        String installType,
        String npmPackage,
        String transportType,
        String endpoint) {

    /** Keeps a running development instance compatible while its service class is reloaded. */
    public McpRepositoryView(
            String id,
            String name,
            String description,
            String url,
            String defaultBranch,
            int stars,
            String sourceType) {
        this(id, name, description, url, defaultBranch, stars, sourceType, "REPOSITORY", null, null, null);
    }
}
