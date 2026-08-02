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
import java.util.List;
import org.springframework.stereotype.Service;

/** Thin adapter for the public ClawHub registry API. */
@Service
public class ClawHubSkillService {

    private static final String REGISTRY = "https://clawhub.ai";
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public ClawHubSkillService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<ClawHubSkillView> search(String query, Integer limit) {
        int boundedLimit = Math.max(1, Math.min(limit == null ? 24 : limit, 50));
        boolean popular = query == null || query.isBlank();
        JsonNode response = popular
                ? getJson("/api/v1/trending?limit=" + boundedLimit)
                : getJson("/api/v1/search?q=" + URLEncoder.encode(query.trim(), StandardCharsets.UTF_8)
                        + "&limit=" + boundedLimit);
        List<ClawHubSkillView> results = new ArrayList<>();
        for (JsonNode entry : response.path(popular ? "items" : "results")) {
            String owner = text(entry, "ownerHandle", text(entry.path("owner"), "handle",
                    text(entry.path("publisher"), "handle", text(entry.path("sourceIdentity"), "owner", ""))));
            String slug = text(entry, "slug", "");
            String reference = text(entry.path("install"), "reference", owner + "/" + slug);
            if (owner.isBlank() || slug.isBlank() || reference.equals("/")) continue;
            JsonNode trust = entry.path("trust");
            results.add(new ClawHubSkillView(
                    text(entry, "id", "clawhub:" + owner + "/" + slug),
                    text(entry, "displayName", slug),
                    text(entry, "summary", ""),
                    reference,
                    REGISTRY + text(entry.path("links"), "canonical", "/" + owner + "/skills/" + slug),
                    entry.path("downloads").asLong(entry.path("metrics").path("trending24hDownloads")
                            .asLong(entry.path("native").path("skill").path("stats").path("downloads").asLong())),
                    entry.path("official").asBoolean(false),
                    entry.path("native").path("skill").path("isSuspicious").asBoolean(false),
                    text(trust, "clawHubVerdict", "unknown")));
        }
        return results;
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
        if (!downloadUrl.startsWith(REGISTRY + "/api/v1/download")) {
            throw new IllegalStateException("ClawHub returned an untrusted archive URL.");
        }
        byte[] archive = getBytes(URI.create(downloadUrl));
        return new ClawHubInstall(
                text(detail.path("skill"), "displayName", slug),
                text(detail.path("skill"), "summary", ""),
                owner + "/" + slug,
                text(resolution.path("archive"), "version", "latest"),
                REGISTRY + "/" + owner + "/skills/" + slug,
                archive);
    }

    private JsonNode getJson(String path) {
        try {
            HttpResponse<String> response = httpClient.send(request(URI.create(REGISTRY + path)), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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
                .header("Accept", "application/json").header("User-Agent", "spring-agent-studio").GET().build();
    }

    private static String encodeSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String text(JsonNode node, String field, String fallback) {
        String value = node.path(field).asText("").trim();
        return value.isBlank() ? fallback : value;
    }

    public record ClawHubInstall(String name, String description, String reference, String version, String sourceUrl, byte[] archive) {
    }
}
