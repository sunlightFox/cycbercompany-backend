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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    private static final String REGISTRY_URL = "https://registry.modelcontextprotocol.io/v0.1/servers?limit=100";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper objectMapper;

    public McpRepositoryService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<McpRepositoryView> curated() {
        try {
            return registryEntries("");
        } catch (IllegalStateException ex) {
            return fallbackEntries();
        }
    }

    public List<McpRepositoryView> search(SearchMcpRepositoriesCommand command) {
        String query = command.query() == null ? "" : command.query().trim();
        int limit = clamp(command.limit(), 1, 30, 20);
        String source = command.source() == null ? "all" : command.source().trim().toLowerCase(Locale.ROOT);
        List<McpRepositoryView> results = new ArrayList<>();
        if (!"github".equals(source)) {
            try {
                results.addAll(registryEntries(query));
            } catch (IllegalStateException ex) {
                results.addAll(fallbackEntries().stream()
                        .filter(entry -> matches(entry, query))
                        .toList());
            }
        }
        if (!"registry".equals(source)) {
            results.addAll(searchGitHub(query, limit));
        }
        return results.stream().limit(limit).toList();
    }

    private List<McpRepositoryView> fallbackEntries() {
        return List.of(
                npmEntry(
                        "modelcontextprotocol-filesystem",
                        "Filesystem",
                        "Official filesystem MCP server.",
                        "https://www.npmjs.com/package/@modelcontextprotocol/server-filesystem",
                        "@modelcontextprotocol/server-filesystem"),
                npmEntry(
                        "modelcontextprotocol-memory",
                        "Memory",
                        "Official memory MCP server.",
                        "https://www.npmjs.com/package/@modelcontextprotocol/server-memory",
                        "@modelcontextprotocol/server-memory"),
                new McpRepositoryView(
                        "modelcontextprotocol-servers",
                        "modelcontextprotocol/servers",
                        "Official/community reference MCP servers and examples.",
                        "https://github.com/modelcontextprotocol/servers",
                        "main",
                        0,
                        "GITHUB_INDEX",
                        "REPOSITORY",
                        null,
                        null,
                        null),
                new McpRepositoryView(
                        "punkpeye-awesome-mcp-servers",
                        "punkpeye/awesome-mcp-servers",
                        "Popular curated list of MCP servers across databases, cloud, browser, search and developer tools.",
                        "https://github.com/punkpeye/awesome-mcp-servers",
                        "main",
                        0,
                        "GITHUB_INDEX",
                        "REPOSITORY",
                        null,
                        null,
                        null),
                new McpRepositoryView(
                        "appcypher-awesome-mcp-servers",
                        "appcypher/awesome-mcp-servers",
                        "Large community index of MCP servers and clients.",
                        "https://github.com/appcypher/awesome-mcp-servers",
                        "main",
                        0,
                        "GITHUB_INDEX",
                        "REPOSITORY",
                        null,
                        null,
                        null),
                new McpRepositoryView(
                        "wong2-awesome-mcp-servers",
                        "wong2/awesome-mcp-servers",
                        "Community maintained collection of MCP servers.",
                        "https://github.com/wong2/awesome-mcp-servers",
                        "main",
                        0,
                        "GITHUB_INDEX",
                        "REPOSITORY",
                        null,
                        null,
                        null));
    }

    private List<McpRepositoryView> searchGitHub(String query, int limit) {
        String searchQuery = query.isBlank()
                ? "model context protocol server mcp"
                : query + " MCP server model context protocol";
        String encoded = URLEncoder.encode(searchQuery, StandardCharsets.UTF_8);
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
                        "GITHUB_SEARCH",
                        "REPOSITORY",
                        null,
                        null,
                        null));
            }
            return results;
        } catch (IOException ex) {
            throw new IllegalStateException("GitHub repository search failed: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("GitHub repository search interrupted", ex);
        }
    }

    private List<McpRepositoryView> registryEntries(String query) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(REGISTRY_URL))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .header("User-Agent", "spring-agent-studio")
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("MCP Registry request failed: HTTP " + response.statusCode());
            }
            Map<String, RegistryCandidate> entries = new LinkedHashMap<>();
            for (JsonNode wrapper : objectMapper.readTree(response.body()).path("servers")) {
                JsonNode server = wrapper.path("server");
                String name = text(server, "title", text(server, "name", ""));
                String description = text(server, "description", "");
                String url = text(server, "websiteUrl", "https://registry.modelcontextprotocol.io");
                JsonNode npm = firstNpmPackage(server.path("packages"));
                JsonNode remote = firstRemote(server.path("remotes"));
                boolean latest = wrapper.path("_meta")
                        .path("io.modelcontextprotocol.registry/official")
                        .path("isLatest")
                        .asBoolean(false);
                McpRepositoryView entry = null;
                if (!npm.isMissingNode()) {
                    entry = new McpRepositoryView(
                            text(server, "name", name), name, description, url, text(server, "version", ""), 0,
                            "MCP_REGISTRY", "NPM", text(npm, "identifier", ""), "STDIO", null);
                } else if (!remote.isMissingNode()) {
                    String type = text(remote, "type", "streamable-http");
                    entry = new McpRepositoryView(
                            text(server, "name", name), name, description, url, text(server, "version", ""), 0,
                            "MCP_REGISTRY", "REMOTE", null, "sse".equalsIgnoreCase(type) ? "SSE" : "STREAMABLE_HTTP",
                            text(remote, "url", null));
                }
                if (entry != null) {
                    String key = text(server, "name", entry.id());
                    RegistryCandidate candidate = new RegistryCandidate(entry, latest);
                    entries.merge(key, candidate, McpRepositoryService::preferredCandidate);
                }
            }
            return entries.values().stream()
                    .map(RegistryCandidate::entry)
                    .filter(entry -> matches(entry, query))
                    .sorted(Comparator.comparing(McpRepositoryView::name, String.CASE_INSENSITIVE_ORDER))
                    .toList();
        } catch (IOException ex) {
            throw new IllegalStateException("MCP Registry request failed: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("MCP Registry request interrupted", ex);
        }
    }

    private static McpRepositoryView npmEntry(String id, String name, String description, String url, String npmPackage) {
        return new McpRepositoryView(id, name, description, url, "", 0, "MCP_REGISTRY", "NPM", npmPackage, "STDIO", null);
    }

    private static JsonNode firstNpmPackage(JsonNode packages) {
        for (JsonNode entry : packages) {
            if ("npm".equalsIgnoreCase(text(entry, "registryType", "")) && !text(entry, "identifier", "").isBlank()) {
                return entry;
            }
        }
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    private static JsonNode firstRemote(JsonNode remotes) {
        for (JsonNode entry : remotes) {
            if (!text(entry, "url", "").isBlank()) {
                return entry;
            }
        }
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    private static boolean matches(McpRepositoryView entry, String query) {
        if (query == null || query.isBlank()) return true;
        String needle = query.toLowerCase(Locale.ROOT);
        return (entry.name() + " " + entry.description() + " " + entry.npmPackage()).toLowerCase(Locale.ROOT).contains(needle);
    }

    private static String text(JsonNode node, String field, String fallback) {
        String value = node.path(field).asText("");
        return value.isBlank() ? fallback : value;
    }

    private static RegistryCandidate preferredCandidate(RegistryCandidate current, RegistryCandidate candidate) {
        if (candidate.latest() != current.latest()) {
            return candidate.latest() ? candidate : current;
        }
        if ("NPM".equals(candidate.entry().installType()) != "NPM".equals(current.entry().installType())) {
            return "NPM".equals(candidate.entry().installType()) ? candidate : current;
        }
        return current;
    }

    private record RegistryCandidate(McpRepositoryView entry, boolean latest) {
    }

    private static int clamp(Integer value, int min, int max, int fallback) {
        int resolved = value == null ? fallback : value;
        return Math.max(min, Math.min(max, resolved));
    }
}
