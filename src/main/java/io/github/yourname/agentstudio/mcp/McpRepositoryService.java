package io.github.yourname.agentstudio.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Finds MCP server repositories for the management UI.
 *
 * <p>The result is not automatically trusted or executed. It is a discovery
 * aid so admins can inspect a repository, then create a managed MCP connection
 * with explicit command/env/tool settings.
 */
@Service
public class McpRepositoryService {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper objectMapper;

    public McpRepositoryService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<McpRepositoryView> curated() {
        return List.of(
                new McpRepositoryView(
                        "modelcontextprotocol-servers",
                        "modelcontextprotocol/servers",
                        "Official/community reference MCP servers and examples.",
                        "https://github.com/modelcontextprotocol/servers",
                        "main",
                        0,
                        "CURATED"),
                new McpRepositoryView(
                        "punkpeye-awesome-mcp-servers",
                        "punkpeye/awesome-mcp-servers",
                        "Popular curated list of MCP servers across databases, cloud, browser, search and developer tools.",
                        "https://github.com/punkpeye/awesome-mcp-servers",
                        "main",
                        0,
                        "CURATED"),
                new McpRepositoryView(
                        "appcypher-awesome-mcp-servers",
                        "appcypher/awesome-mcp-servers",
                        "Large community index of MCP servers and clients.",
                        "https://github.com/appcypher/awesome-mcp-servers",
                        "main",
                        0,
                        "CURATED"),
                new McpRepositoryView(
                        "wong2-awesome-mcp-servers",
                        "wong2/awesome-mcp-servers",
                        "Community maintained collection of MCP servers.",
                        "https://github.com/wong2/awesome-mcp-servers",
                        "main",
                        0,
                        "CURATED"));
    }

    public List<McpRepositoryView> search(SearchMcpRepositoriesCommand command) {
        String query = command.query() == null || command.query().isBlank()
                ? "model context protocol server mcp"
                : command.query().trim() + " MCP server model context protocol";
        int limit = clamp(command.limit(), 1, 20, 10);
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        URI uri = URI.create("https://api.github.com/search/repositories?q=" + encoded
                + "&sort=stars&order=desc&per_page=" + limit);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "spring-agent-studio")
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("GitHub repository search failed: HTTP " + response.statusCode());
            }
            JsonNode json = objectMapper.readTree(response.body());
            List<McpRepositoryView> results = new ArrayList<>();
            for (JsonNode item : json.path("items")) {
                results.add(new McpRepositoryView(
                        item.path("full_name").asText(),
                        item.path("full_name").asText(),
                        item.path("description").asText(""),
                        item.path("html_url").asText(),
                        item.path("default_branch").asText("main"),
                        item.path("stargazers_count").asInt(),
                        "GITHUB_SEARCH"));
            }
            return results;
        } catch (IOException ex) {
            throw new IllegalStateException("GitHub repository search failed: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("GitHub repository search interrupted", ex);
        }
    }

    private static int clamp(Integer value, int min, int max, int fallback) {
        int resolved = value == null ? fallback : value;
        return Math.max(min, Math.min(max, resolved));
    }
}
