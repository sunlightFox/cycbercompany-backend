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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Thin adapter for the public ClawHub registry API. */
@Service
public class ClawHubSkillService {

    private final URI registryBase;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Autowired
    public ClawHubSkillService(ObjectMapper objectMapper) {
        this(objectMapper, defaultHttpClient(), URI.create("https://clawhub.ai"));
    }

    ClawHubSkillService(ObjectMapper objectMapper, HttpClient httpClient, URI registryBase) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.registryBase = registryBase;
    }

    public List<ClawHubSkillView> search(String query, Integer limit) {
        int boundedLimit = Math.max(1, Math.min(limit == null ? 24 : limit, 50));
        String normalizedQuery = query == null ? "" : query.trim();
        int perSourceLimit = Math.max(8, boundedLimit);
        List<ClawHubSkillView> collected = new ArrayList<>();

        try {
            collected.addAll(trending(perSourceLimit));
        } catch (RuntimeException ignored) {
            // A weak source should not eliminate the rest of the marketplace.
        }
        try {
            collected.addAll(searchQuery(normalizedQuery.isBlank() ? "skill" : normalizedQuery, perSourceLimit));
        } catch (RuntimeException ignored) {
            // Keep whatever other results are available.
        }

        Map<String, ClawHubHit> merged = new LinkedHashMap<>();
        for (int index = 0; index < collected.size(); index++) {
            ClawHubSkillView view = collected.get(index);
            String key = dedupeKey(view);
            merged.merge(key, new ClawHubHit(view, index), ClawHubSkillService::preferHit);
        }

        return merged.values().stream()
                .sorted(Comparator
                        .comparingLong((ClawHubHit hit) -> hit.view().downloads()).reversed()
                        .thenComparingInt(ClawHubHit::rank)
                        .thenComparing(hit -> hit.view().name(), String.CASE_INSENSITIVE_ORDER))
                .limit(boundedLimit)
                .map(ClawHubHit::view)
                .toList();
    }

    public ClawHubInstall download(String reference) {
        String[] parts = reference == null ? new String[0] : reference.trim().replaceFirst("^@", "").split("/", -1);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException("ClawHub reference must be owner/skill.");
        }
        String owner = parts[0];
        String slug = parts[1];
        String ownerParam = "?ownerHandle=" + URLEncoder.encode(owner, StandardCharsets.UTF_8);
        JsonNode detail = getJson("/api/v1/skills/" + encodeSegment(slug) + ownerParam);
        JsonNode moderation = detail.path("moderation");
        if (moderation.path("isMalwareBlocked").asBoolean(false)) {
            throw new IllegalArgumentException("ClawHub blocked this skill as malware.");
        }
        if (moderation.path("isSuspicious").asBoolean(false)) {
            throw new IllegalArgumentException("ClawHub marked this skill as suspicious; review it before installing.");
        }
        JsonNode resolution = getJson("/api/v1/skills/" + encodeSegment(slug) + "/install" + ownerParam);
        if (!resolution.path("ok").asBoolean(false) || !"archive".equals(text(resolution, "installKind", ""))) {
            throw new IllegalArgumentException(text(resolution, "message", "ClawHub could not resolve an installable archive."));
        }
        String downloadUrl = text(resolution.path("archive"), "downloadUrl", "");
        if (!downloadUrl.startsWith(baseUrl() + "/api/v1/download")) {
            throw new IllegalStateException("ClawHub returned an untrusted archive URL.");
        }
        byte[] archive = getBytes(URI.create(downloadUrl));
        return new ClawHubInstall(
                text(detail.path("skill"), "displayName", slug),
                text(detail.path("skill"), "summary", ""),
                owner + "/" + slug,
                text(resolution.path("archive"), "version", "latest"),
                baseUrl() + "/" + owner + "/skills/" + slug,
                archive);
    }

    private List<ClawHubSkillView> trending(int limit) {
        return getEntries("/api/v1/trending?limit=" + limit, true);
    }

    private List<ClawHubSkillView> searchQuery(String query, int limit) {
        return getEntries("/api/v1/search?q=" + URLEncoder.encode(query.trim(), StandardCharsets.UTF_8)
                + "&limit=" + limit, false);
    }

    private List<ClawHubSkillView> getEntries(String path, boolean popular) {
        JsonNode response = getJson(path);
        List<ClawHubSkillView> results = new ArrayList<>();
        for (JsonNode entry : response.path(popular ? "items" : "results")) {
            String owner = text(entry, "ownerHandle", text(entry.path("owner"), "handle",
                    text(entry.path("publisher"), "handle", text(entry.path("sourceIdentity"), "owner", ""))));
            String slug = text(entry, "slug", "");
            String reference = text(entry.path("install"), "reference", owner + "/" + slug);
            if (owner.isBlank() || slug.isBlank() || reference.equals("/")) {
                continue;
            }
            JsonNode trust = entry.path("trust");
            results.add(new ClawHubSkillView(
                    text(entry, "id", "clawhub:" + owner + "/" + slug),
                    text(entry, "displayName", slug),
                    text(entry, "summary", ""),
                    reference,
                    baseUrl() + text(entry.path("links"), "canonical", "/" + owner + "/skills/" + slug),
                    entry.path("downloads").asLong(entry.path("metrics").path("trending24hDownloads")
                            .asLong(entry.path("native").path("skill").path("stats").path("downloads").asLong())),
                    entry.path("official").asBoolean(false),
                    entry.path("native").path("skill").path("isSuspicious").asBoolean(false),
                    text(trust, "clawHubVerdict", "unknown")));
        }
        return results;
    }

    private JsonNode getJson(String path) {
        try {
            HttpResponse<String> response = httpClient.send(request(URI.create(baseUrl() + path)), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("ClawHub request failed: HTTP " + response.statusCode());
            }
            return objectMapper.readTree(response.body());
        } catch (IOException ex) {
            throw new IllegalStateException("ClawHub request failed: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ClawHub request interrupted", ex);
        }
    }

    private byte[] getBytes(URI uri) {
        try {
            HttpResponse<byte[]> response = httpClient.send(request(uri), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("ClawHub archive download failed: HTTP " + response.statusCode());
            }
            return response.body();
        } catch (IOException ex) {
            throw new IllegalStateException("ClawHub archive download failed: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ClawHub archive download interrupted", ex);
        }
    }

    private HttpRequest request(URI uri) {
        return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json").header("User-Agent", "cycbercompany").GET().build();
    }

    private String baseUrl() {
        String value = registryBase.toString();
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String encodeSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String text(JsonNode node, String field, String fallback) {
        String value = node.path(field).asText("").trim();
        return value.isBlank() ? fallback : value;
    }

    private static String dedupeKey(ClawHubSkillView view) {
        return (view.reference() == null || view.reference().isBlank() ? view.id() : view.reference()).toLowerCase(Locale.ROOT);
    }

    private static ClawHubHit preferHit(ClawHubHit current, ClawHubHit candidate) {
        if (candidate.view().official() && !current.view().official()) {
            return candidate;
        }
        if (candidate.view().downloads() > current.view().downloads()) {
            return candidate;
        }
        if (candidate.rank() < current.rank()) {
            return candidate;
        }
        return current;
    }

    private static HttpClient defaultHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public record ClawHubInstall(String name, String description, String reference, String version, String sourceUrl, byte[] archive) {
    }

    private record ClawHubHit(ClawHubSkillView view, int rank) {
    }
}
