package io.github.yourname.cycbercompany.mcp;

/**
 * Search command for discovering public MCP server repositories.
 */
public record SearchMcpRepositoriesCommand(String query, Integer limit, String source) {
}
