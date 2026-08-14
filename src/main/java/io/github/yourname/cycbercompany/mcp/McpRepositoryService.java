package io.github.yourname.cycbercompany.mcp;

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
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Discovers MCP servers exclusively from MCPMarket.cn. */
@Service
public class McpRepositoryService {
    public static final String MARKET_URL = "https://mcpmarket.cn";
    private static final List<McpRepositoryView> FALLBACK_ENTRIES = List.of(
            repository("modelcontextprotocol-servers", "modelcontextprotocol/servers",
                    "Reference MCP servers and examples from the Model Context Protocol project.",
                    "https://github.com/modelcontextprotocol/servers", "OFFICIAL"),
            repository("modelcontextprotocol-servers-archived", "modelcontextprotocol/servers-archived",
                    "Archived reference implementations for established MCP server patterns.",
                    "https://github.com/modelcontextprotocol/servers-archived", "OFFICIAL"),
            repository("punkpeye-awesome-mcp-servers", "punkpeye/awesome-mcp-servers",
                    "Community-maintained catalog of MCP servers across development and data workflows.",
                    "https://github.com/punkpeye/awesome-mcp-servers", "COMMUNITY_INDEX"),
            repository("appcypher-awesome-mcp-servers", "appcypher/awesome-mcp-servers",
                    "Curated index of MCP servers, clients, and related ecosystem resources.",
                    "https://github.com/appcypher/awesome-mcp-servers", "COMMUNITY_INDEX"));
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI marketBaseUri;

    @Autowired
    public McpRepositoryService(ObjectMapper objectMapper) {
        this(objectMapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build(), URI.create(MARKET_URL));
    }

    McpRepositoryService(ObjectMapper objectMapper, HttpClient httpClient, URI marketBaseUri) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.marketBaseUri = marketBaseUri;
    }

    public List<McpRepositoryView> curated() {
        return withFallback("", 100);
    }

    public List<McpRepositoryView> search(SearchMcpRepositoriesCommand command) {
        String query = command == null || command.query() == null ? "" : command.query().trim();
        int limit = clamp(command == null ? null : command.limit(), 1, 100, 20);
        // source is retained for API compatibility, but MCPMarket is the only source.
        return withFallback(query, limit);
    }

    /** Resolves the install URL from an MCPMarket detail record for one-click install. */
    public String installableEndpoint(String id) {
        if (id == null || id.isBlank() || !id.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("A valid MCPMarket server id is required.");
        }
        HttpRequest request = HttpRequest.newBuilder(marketUri("/api/servers/" + id))
                .timeout(Duration.ofSeconds(20)).header("Accept", "application/json").GET().build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("MCPMarket detail request failed: HTTP " + response.statusCode());
            }
            JsonNode item = objectMapper.readTree(response.body());
            String endpoint = endpoint(item);
            if (endpoint == null) endpoint = endpointFromMarkdown(text(item, "detail_content", ""));
            if (endpoint == null || !endpoint.startsWith("https://")) throw new IllegalArgumentException("MCPMarket entry has no trusted HTTPS endpoint.");
            return endpoint;
        } catch (IOException ex) { throw new IllegalStateException("MCPMarket detail request failed: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException("MCPMarket detail request interrupted", ex); }
    }

    private List<McpRepositoryView> fetch(String query, int limit) {
        String path = "/api/servers?page=1&per_page=" + limit;
        if (!query.isBlank()) {
            path += "&search=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
        }
        HttpRequest request = HttpRequest.newBuilder(marketUri(path))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .header("User-Agent", "cycbercompany")
                .GET().build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("MCPMarket request failed: HTTP " + response.statusCode());
            }
            List<McpRepositoryView> results = new ArrayList<>();
            for (JsonNode item : objectMapper.readTree(response.body()).path("servers")) {
                String id = text(item, "_id", text(item, "name", ""));
                String name = text(item, "name", text(item, "alias", id));
                String description = description(item.path("description"));
                // MCPMarket's `url` field points to the implementation repository (often GitHub).
                // The management UI must always link back to the selected MCPMarket entry.
                String url = marketUri("/server/" + id).toString();
                String endpoint = endpoint(item);
                results.add(new McpRepositoryView(id, name, description, url, "", item.path("stars").asInt(0),
                        "MCPMARKET", endpoint == null ? "REPOSITORY" : "REMOTE", null,
                        endpoint == null ? null : "STREAMABLE_HTTP", endpoint));
            }
            return results;
        } catch (IOException ex) {
            throw new IllegalStateException("MCPMarket request failed: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("MCPMarket request interrupted", ex);
        }
    }

    private List<McpRepositoryView> withFallback(String query, int limit) {
        try {
            List<McpRepositoryView> results = fetch(query, limit);
            return results.isEmpty() ? fallback(query, limit) : results;
        } catch (IllegalStateException ex) {
            return fallback(query, limit);
        }
    }

    private static List<McpRepositoryView> fallback(String query, int limit) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<McpRepositoryView> matches = FALLBACK_ENTRIES.stream()
                .filter(entry -> needle.isBlank() || (entry.name() + " " + entry.description())
                        .toLowerCase(Locale.ROOT).contains(needle))
                .sorted(Comparator.comparing(McpRepositoryView::name, String.CASE_INSENSITIVE_ORDER))
                .limit(limit)
                .toList();
        return matches.isEmpty() ? FALLBACK_ENTRIES.stream().limit(limit).toList() : matches;
    }

    private static McpRepositoryView repository(
            String id, String name, String description, String url, String sourceType) {
        return new McpRepositoryView(id, name, description, url, "main", 0, sourceType,
                "REPOSITORY", null, null, null);
    }

    private static String description(JsonNode node) {
        if (node.isObject()) return text(node, "zh", text(node, "en", ""));
        return node.asText("");
    }

    private URI marketUri(String path) {
        String base = marketBaseUri.toString().replaceAll("/+$", "");
        return URI.create(base + path);
    }

    private static String endpoint(JsonNode item) {
        for (String field : List.of("mcp_url", "endpoint")) {
            String value = text(item, field, null);
            if (isHttps(value)) return value;
        }
        for (String field : List.of("mcp_config", "config", "remote", "remotes")) {
            JsonNode value = item.path(field);
            if (value.isObject()) {
                String found = endpoint(value);
                if (found != null) return found;
            } else if (value.isArray()) {
                for (JsonNode entry : value) {
                    String found = endpoint(entry);
                    if (found != null) return found;
                }
            }
        }
        return null;
    }

    private static String endpointFromMarkdown(String markdown) {
        Matcher matcher = Pattern.compile("\\\"(?:url|endpoint)\\\"\\s*:\\s*\\\"(https://[^\\\"]+)\\\"").matcher(markdown);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static boolean isHttps(String value) {
        return value != null && value.startsWith("https://");
    }

    private static String text(JsonNode node, String field, String fallback) {
        String value = node.path(field).asText("");
        return value.isBlank() ? fallback : value;
    }

    private static int clamp(Integer value, int min, int max, int fallback) {
        int resolved = value == null ? fallback : value;
        return Math.max(min, Math.min(max, resolved));
    }
}
