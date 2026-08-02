package io.github.yourname.agentstudio.skill;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.springframework.stereotype.Service;

/**
 * Discovers skill repositories and skill directories hosted on GitHub.
 *
 * <p>This class is intentionally GitHub-specific because the current public
 * skill ecosystem mostly distributes {@code SKILL.md} folders through GitHub.
 * The rest of the backend depends on neutral DTOs, so adding npm/package-index
 * style registries later only requires another discovery provider.
 */
@Service
public class SkillRepositoryService {

    private static final Pattern GITHUB_URL = Pattern.compile(
            "https?://github\\.com/([^/]+)/([^/#?]+)(?:/.*)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern FRONT_MATTER_FIELD = Pattern.compile(
            "(?m)^([A-Za-z][A-Za-z0-9_-]*)\\s*:\\s*(.+)$");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * Curated sources chosen from currently visible public GitHub skill
     * ecosystem: one large cross-agent catalog, the official Anthropic skill
     * examples, and one Codex-focused list. They are plain GitHub repositories,
     * so users can audit everything before installing.
     */
    public List<SkillRepositoryView> curated() {
        return List.of(
                new SkillRepositoryView(
                        "voltagent-awesome-agent-skills",
                        "VoltAgent/awesome-agent-skills",
                        "Large community catalog of Agent Skills compatible with Codex, Claude Code, Gemini CLI and more.",
                        "https://github.com/VoltAgent/awesome-agent-skills",
                        "main",
                        0,
                        "CURATED"),
                new SkillRepositoryView(
                        "anthropics-skills",
                        "anthropics/skills",
                        "Public repository for Agent Skills using the SKILL.md folder convention.",
                        "https://github.com/anthropics/skills",
                        "main",
                        0,
                        "CURATED"),
                new SkillRepositoryView(
                        "composio-awesome-codex-skills",
                        "composio-community/awesome-codex-skills",
                        "Codex-focused collection of modular instruction bundles.",
                        "https://github.com/composio-community/awesome-codex-skills",
                        "main",
                        0,
                        "CURATED"),
                new SkillRepositoryView(
                        "ethanyoq-skill-hub",
                        "EthanYoQ/Skill-hub",
                        "Community SkillHub collection of reusable Codex and Claude Code workflows.",
                        "https://github.com/EthanYoQ/Skill-hub",
                        "mine",
                        0,
                        "CURATED"));
    }

    /**
     * Searches public repositories likely to contain skills. GitHub may apply
     * unauthenticated rate limits; when that happens the curated list still
     * gives the UI a useful fallback.
     */
    public List<SkillRepositoryView> search(SearchSkillRepositoriesCommand command) {
        String query = command.query() == null || command.query().isBlank()
                ? "agent skills SKILL.md topic:codex-skills"
                : command.query().trim() + " SKILL.md agent skills";
        int limit = clamp(command.limit(), 1, 20, 10);
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        URI uri = URI.create("https://api.github.com/search/repositories?q=" + encoded
                + "&sort=stars&order=desc&per_page=" + limit);
        JsonNode json = getJson(uri);
        List<SkillRepositoryView> results = new ArrayList<>();
        for (JsonNode item : json.path("items")) {
            results.add(new SkillRepositoryView(
                    item.path("full_name").asText(),
                    item.path("full_name").asText(),
                    item.path("description").asText(""),
                    item.path("html_url").asText(),
                    item.path("default_branch").asText("main"),
                    item.path("stargazers_count").asInt(),
                    "GITHUB_SEARCH"));
        }
        return results;
    }

    /**
     * Discovers every {@code SKILL.md} path in a repository and reads a small
     * preview from raw GitHub content for display in the skill marketplace.
     */
    public List<RepositorySkillView> discover(DiscoverRepositorySkillsCommand command) {
        GitHubRepository repo = parseGitHubRepository(command.repoUrl());
        String ref = command.ref() == null || command.ref().isBlank() ? "main" : command.ref().trim();
        int limit = clamp(command.limit(), 1, 100, 50);
        List<ArchiveSkillFile> skillFiles = discoverFromZip(repo, ref);
        if (skillFiles.isEmpty() && command.ref() == null) {
            ref = "master";
            skillFiles = discoverFromZip(repo, ref);
        }
        Map<String, RepositorySkillView> discovered = new LinkedHashMap<>();
        for (ArchiveSkillFile skillFile : skillFiles.stream()
                .sorted(Comparator.comparing(ArchiveSkillFile::path))
                .limit(limit)
                .toList()) {
            String skillPath = skillFile.path().equals("SKILL.md")
                    ? ""
                    : skillFile.path().substring(0, skillFile.path().length() - "/SKILL.md".length());
            SkillPreview preview = parsePreview(skillFile.markdown(), skillFile.path());
            discovered.put(skillPath, new RepositorySkillView(
                    preview.name(),
                    preview.description(),
                    command.repoUrl(),
                    ref,
                    skillPath,
                    normalizeSkillId(preview.name())));
        }
        return discovered.values().stream()
                .sorted(Comparator.comparing(RepositorySkillView::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private List<ArchiveSkillFile> discoverFromZip(GitHubRepository repo, String ref) {
        URI uri = URI.create("https://codeload.github.com/" + repo.owner() + "/" + repo.name() + "/zip/" + ref);
        HttpRequest request = request(uri).GET().build();
        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return List.of();
            }
            if (response.body().length > 25 * 1024 * 1024) {
                throw new IllegalStateException("Repository archive is too large for online preview.");
            }
            List<ArchiveSkillFile> skills = new ArrayList<>();
            try (InputStream input = new ByteArrayInputStream(response.body());
                    ZipInputStream zip = new ZipInputStream(input)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (entry.isDirectory()) {
                        continue;
                    }
                    String path = stripTopLevelDirectory(entry.getName());
                    if (path == null) {
                        continue;
                    }
                    if (path.equals("SKILL.md") || path.endsWith("/SKILL.md")) {
                        byte[] markdown = zip.readNBytes(128 * 1024);
                        skills.add(new ArchiveSkillFile(path, new String(markdown, StandardCharsets.UTF_8)));
                    }
                }
            }
            return skills;
        } catch (IOException ex) {
            throw new IllegalStateException("GitHub archive preview failed: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("GitHub archive preview interrupted", ex);
        }
    }

    private SkillPreview fetchSkillPreview(GitHubRepository repo, String ref, String skillFile) {
        String encodedPath = encodePath(skillFile);
        URI uri = URI.create("https://raw.githubusercontent.com/" + repo.owner() + "/" + repo.name()
                + "/" + ref + "/" + encodedPath);
        HttpRequest request = request(uri).GET().build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return new SkillPreview(nameFromPath(skillFile), "Skill metadata could not be previewed.");
            }
            return parsePreview(response.body(), skillFile);
        } catch (IOException ex) {
            return new SkillPreview(nameFromPath(skillFile), "Preview failed: " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new SkillPreview(nameFromPath(skillFile), "Preview interrupted.");
        }
    }

    private SkillPreview parsePreview(String markdown, String skillFile) {
        String name = null;
        String description = null;
        if (markdown.startsWith("---")) {
            int end = markdown.indexOf("\n---", 3);
            if (end > 0) {
                Matcher matcher = FRONT_MATTER_FIELD.matcher(markdown.substring(3, end));
                while (matcher.find()) {
                    String key = matcher.group(1).toLowerCase(Locale.ROOT);
                    String value = stripQuotes(matcher.group(2).trim());
                    if (key.equals("name")) {
                        name = value;
                    } else if (key.equals("description")) {
                        description = value;
                    }
                }
            }
        }
        if (name == null || name.isBlank()) {
            name = markdown.lines()
                    .filter(line -> line.startsWith("# "))
                    .map(line -> line.substring(2).trim())
                    .findFirst()
                    .orElse(nameFromPath(skillFile));
        }
        if (description == null || description.isBlank()) {
            description = markdown.lines()
                    .map(String::trim)
                    .filter(line -> !line.isBlank() && !line.startsWith("---") && !line.startsWith("#"))
                    .findFirst()
                    .orElse("No description provided.");
        }
        return new SkillPreview(name, description);
    }

    private String defaultBranch(GitHubRepository repo) {
        URI uri = URI.create("https://api.github.com/repos/" + repo.owner() + "/" + repo.name());
        JsonNode json = getJson(uri);
        return json.path("default_branch").asText("main");
    }

    private static String stripTopLevelDirectory(String zipEntryName) {
        String normalized = zipEntryName.replace('\\', '/');
        int slash = normalized.indexOf('/');
        if (slash < 0 || slash == normalized.length() - 1) {
            return null;
        }
        return normalized.substring(slash + 1);
    }

    private JsonNode getJson(URI uri) {
        HttpRequest request = request(uri).GET().build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("GitHub request failed: HTTP " + response.statusCode() + " for " + uri);
            }
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.body());
        } catch (IOException ex) {
            throw new IllegalStateException("GitHub request failed: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("GitHub request interrupted", ex);
        }
    }

    private HttpRequest.Builder request(URI uri) {
        return HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "spring-agent-studio");
    }

    private GitHubRepository parseGitHubRepository(String repoUrl) {
        Matcher matcher = GITHUB_URL.matcher(repoUrl == null ? "" : repoUrl.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Only GitHub repository URLs are supported.");
        }
        return new GitHubRepository(matcher.group(1), matcher.group(2).replaceAll("\\.git$", ""));
    }

    private static String encodePath(String path) {
        return java.util.Arrays.stream(path.split("/"))
                .map(part -> URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20"))
                .reduce((left, right) -> left + "/" + right)
                .orElse("");
    }

    private static int clamp(Integer value, int min, int max, int fallback) {
        int resolved = value == null ? fallback : value;
        return Math.max(min, Math.min(max, resolved));
    }

    private static String normalizeSkillId(String value) {
        String id = (value == null ? "skill" : value)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("^-+|-+$", "");
        return id.isBlank() ? "skill" : id;
    }

    private static String nameFromPath(String skillFile) {
        String path = skillFile.replace('\\', '/');
        if (path.equals("SKILL.md")) {
            return "Repository Skill";
        }
        String[] parts = path.split("/");
        return parts.length >= 2 ? parts[parts.length - 2] : "Repository Skill";
    }

    private static String stripQuotes(String value) {
        return value.replaceAll("^['\"]|['\"]$", "");
    }

    private record GitHubRepository(String owner, String name) {
    }

    private record SkillPreview(String name, String description) {
    }

    private record ArchiveSkillFile(String path, String markdown) {
    }
}
