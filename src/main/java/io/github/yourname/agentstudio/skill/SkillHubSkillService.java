package io.github.yourname.agentstudio.skill;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Thin adapter for SkillHub's public skills API. */
@Service
public class SkillHubSkillService {
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final URI apiBase;

    @Autowired
    public SkillHubSkillService(ObjectMapper objectMapper) {
        this(objectMapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NORMAL).build(), URI.create("https://api.skillhub.cn"));
    }

    SkillHubSkillService(ObjectMapper objectMapper, HttpClient httpClient, URI apiBase) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.apiBase = apiBase;
    }

    public List<SkillHubSkillView> search(String query, Integer limit) {
        int boundedLimit = Math.max(1, Math.min(limit == null ? 100 : limit, 100));
        StringBuilder path = new StringBuilder("/api/skills?page=1&pageSize=").append(boundedLimit)
                .append("&sortBy=score&order=desc");
        if (query != null && !query.trim().isEmpty()) {
            path.append("&keyword=").append(URLEncoder.encode(query.trim(), StandardCharsets.UTF_8));
        }
        JsonNode response = getJson(path.toString());
        if (response.path("code").asInt(-1) != 0) {
            throw new IllegalStateException("SkillHub returned an error: " + response.path("message").asText("unknown"));
        }
        List<SkillHubSkillView> results = new ArrayList<>();
        for (JsonNode entry : response.path("data").path("skills")) {
            String slug = text(entry, "slug");
            String owner = text(entry.path("namespace"), "handle");
            if (slug.isBlank()) continue;
            String reference = owner.isBlank() ? slug : owner + "/" + slug;
            // The API host responds with JSON (and rejects browser navigation). Link users
            // to SkillHub's public skill page instead.
            String homepage = "https://skillhub.cn/skills/" + encodePath(owner) + "/" + encodePath(slug);
            results.add(new SkillHubSkillView(
                    "skillhub:" + reference,
                    text(entry, "name", slug),
                    text(entry, "description", text(entry, "description_zh", "")),
                    reference,
                    homepage,
                    entry.path("downloads").asLong(0),
                    entry.path("verified").asBoolean(false),
                    text(entry, "source", "community")));
        }
        return results.stream().sorted(Comparator.comparingLong(SkillHubSkillView::downloads).reversed()).limit(boundedLimit).toList();
    }

    public ClawHubSkillService.ClawHubInstall download(String reference) {
        String value = reference == null ? "" : reference.trim().replaceFirst("^@", "");
        String[] parts = value.split("/", -1);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException("SkillHub reference must be owner/skill.");
        }
        String slug = parts[1];
        byte[] archive = getBytes("/api/v1/download?slug=" + URLEncoder.encode(slug, StandardCharsets.UTF_8)
                + "&namespace=" + URLEncoder.encode(parts[0], StandardCharsets.UTF_8));
        return new ClawHubSkillService.ClawHubInstall(slug, "", value, "latest",
                apiBase.toString() + "/" + value, archive);
    }

    private JsonNode getJson(String path) {
        try {
            HttpRequest request = HttpRequest.newBuilder(apiBase.resolve(path)).timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/json").header("User-Agent", "spring-agent-studio").GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new IllegalStateException("SkillHub request failed: HTTP " + response.statusCode());
            return objectMapper.readTree(response.body());
        } catch (IOException ex) {
            throw new IllegalStateException("SkillHub request failed: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("SkillHub request interrupted", ex);
        }
    }

    private byte[] getBytes(String path) {
        try {
            HttpRequest request = HttpRequest.newBuilder(apiBase.resolve(path)).timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/zip").header("User-Agent", "spring-agent-studio").GET().build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new IllegalStateException("SkillHub download failed: HTTP " + response.statusCode());
            return response.body();
        } catch (IOException ex) { throw new IllegalStateException("SkillHub download failed: " + ex.getMessage(), ex); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException("SkillHub download interrupted", ex); }
    }

    private static String text(JsonNode node, String field) { return text(node, field, ""); }
    private static String text(JsonNode node, String field, String fallback) {
        String value = node.path(field).asText("").trim();
        return value.isBlank() ? fallback : value;
    }

    private static String encodePath(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
