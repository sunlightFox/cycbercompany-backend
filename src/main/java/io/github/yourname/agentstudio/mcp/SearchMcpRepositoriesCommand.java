package io.github.yourname.agentstudio.mcp;

/**
 * Search command for discovering public MCP server repositories.
 */
public record SearchMcpRepositoriesCommand(String query, Integer limit) {
}
