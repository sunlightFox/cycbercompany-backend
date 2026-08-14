package io.github.yourname.cycbercompany.skill;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Discovers skill repositories and skill directories hosted on GitHub.
 *
 * <p>The service now combines multiple search signals instead of a single narrow
 * query. It also resolves the repository default branch, accepts case-insensitive
 * {@code SKILL.md} filenames, and parses richer front matter for previews.
 */
@Service
public class SkillRepositoryService {

    private static final Pattern GITHUB_URL = Pattern.compile(
            "https?://github\\.com/([^/]+)/([^/#?]+)(?:/.*)?", Pattern.CASE_INSENSITIVE);
    private static final List<SkillRepositoryView> CURATED = List.of(
            seed(
                    "openai-skills",
                    "OpenAI/skills",
                    "Official OpenAI skills collection for reusable task workflows.",
                    "https://github.com/openai/skills",
                    "main",
                    0,
                    "OFFICIAL"),
            seed(
                    "anthropic-skills",
                    "anthropics/skills",
                    "Anthropic's public skills repository with the SKILL.md convention.",
                    "https://github.com/anthropics/skills",
                    "main",
                    0,
                    "OFFICIAL"),
            seed(
                    "nvidia-skills",
                    "nvidia/skills",
                    "NVIDIA skills and examples for agent workflows.",
                    "https://github.com/nvidia/skills",
                    "main",
                    0,
                    "OFFICIAL"),
            seed(
                    "microsoft-skills",
                    "microsoft/skills",
                    "Microsoft Agent Skills, custom agents, AGENTS.md templates, and MCP configurations for Azure SDK and Microsoft AI Foundry workflows.",
                    "https://github.com/microsoft/skills",
                    "main",
                    0,
                    "OFFICIAL"),
            seed(
                    "supabase-agent-skills",
                    "supabase/agent-skills",
                    "Supabase Agent Skills for database, auth, edge functions, storage, and platform workflows.",
                    "https://github.com/supabase/agent-skills",
                    "main",
                    0,
                    "OFFICIAL"),
            seed(
                    "skills-il-cli",
                    "skills-il/skills-il-cli",
                    "Open agent skills tooling and registry references with the SKILL.md convention.",
                    "https://github.com/skills-il/skills-il-cli",
                    "main",
                    0,
                    "REGISTRY"),
            seed(
                    "iflytek-skillhub",
                    "iflytek/skillhub",
                    "SkillHub-style registry with many community skill packages.",
                    "https://github.com/iflytek/skillhub",
                    "main",
                    0,
                    "REGISTRY"),
            seed(
                    "agentskills-spec",
                    "agentskills/agentskills",
                    "Specification and reference material for Agent Skills.",
                    "https://github.com/agentskills/agentskills",
                    "main",
                    0,
                    "OFFICIAL"),
            seed(
                    "voltagent-awesome-agent-skills",
                    "VoltAgent/awesome-agent-skills",
                    "Large community catalog of Agent Skills compatible with Codex, Claude Code, Gemini CLI and more.",
                    "https://github.com/VoltAgent/awesome-agent-skills",
                    "main",
                    0,
                    "COMMUNITY_INDEX"),
            seed(
                    "composio-awesome-codex-skills",
                    "composio-community/awesome-codex-skills",
                    "Codex-focused collection of modular instruction bundles.",
                    "https://github.com/composio-community/awesome-codex-skills",
                    "main",
                    0,
                    "COMMUNITY_INDEX"),
            seed(
                    "skillhub-catalog",
                    "SkillHub catalog",
                    "Primary online SkillHub catalog. Use the SkillHub API for individual skills.",
                    "https://skillhub.cn/",
                    "latest",
                    0,
                    "SKILLHUB"));

    private static final List<String> DEFAULT_SEARCH_QUERIES = List.of(
            "topic:codex-skills",
            "topic:agent-skills",
            "topic:claude-skills",
            "topic:copilot-skills",
            "topic:skill-md",
            "topic:skill-hub",
            "topic:skills",
            "agent skills SKILL.md",
            "codex skills SKILL.md",
            "\"SKILL.md\"",
            "\"skill.md\"");

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ObjectMapper yamlMapper;
    private final URI githubApiBase;
    private final URI githubArchiveBase;

    @Autowired
    public SkillRepositoryService(ObjectMapper objectMapper) {
        this(defaultHttpClient(), objectMapper, URI.create("https://api.github.com"), URI.create("https://codeload.github.com"));
    }

    SkillRepositoryService(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            URI githubApiBase,
            URI githubArchiveBase) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
        this.githubApiBase = githubApiBase;
        this.githubArchiveBase = githubArchiveBase;
    }

    /**
     * Curated sources chosen from the visible public GitHub skill ecosystem.
     */
    public List<SkillRepositoryView> curated() {
        return CURATED;
    }

    /**
     * Searches public repositories likely to contain skills.
     *
     * <p>Several search queries are fanned out and merged so the UI gets a wider
     * sample than a single brittle query can provide.
     */
    public List<SkillRepositoryView> search(SearchSkillRepositoriesCommand command) {
        String query = command.query() == null ? "" : command.query().trim();
        int limit = clamp(command.limit(), 1, 50, 24);
        int perQueryLimit = Math.max(6, Math.min(limit, 15));
        List<String> queries = searchQueries(query);
        Map<String, SearchHit> merged = new LinkedHashMap<>();

        for (int index = 0; index < queries.size(); index++) {
            String searchQuery = queries.get(index);
            try {
                for (SkillRepositoryView item : searchGitHub(searchQuery, perQueryLimit)) {
                    String key = item.id().toLowerCase(Locale.ROOT);
                    SearchHit candidate = new SearchHit(item, index);
                    merged.merge(key, candidate, SkillRepositoryService::preferSearchHit);
                }
            } catch (RuntimeException ex) {
                // A rate limit or transient GitHub outage must not make the online
                // repository screen unusable; curatedFallback handles an empty merge.
            }
        }

        List<SkillRepositoryView> results = merged.values().stream()
                .sorted(Comparator
                        .comparingInt(SearchHit::queryRank)
                        .thenComparing(Comparator.comparingInt((SearchHit hit) -> hit.view().stars()).reversed())
                        .thenComparing(hit -> hit.view().name(), String.CASE_INSENSITIVE_ORDER))
                .limit(limit)
                .map(SearchHit::view)
                .toList();
        return results.isEmpty() ? curatedFallback(query, limit) : results;
    }

    /**
     * Discovers {@code SKILL.md} paths in a repository and reads a preview from
     * the archived content.
     */
    public List<RepositorySkillView> discover(DiscoverRepositorySkillsCommand command) {
        GitHubRepository repo = parseGitHubRepository(command.repoUrl());
        int limit = clamp(command.limit(), 1, 100, 50);
        List<ArchiveSkillFile> skillFiles = discoverFromKnownRefs(repo, command.ref());
        Map<String, RepositorySkillView> discovered = new LinkedHashMap<>();

        for (ArchiveSkillFile skillFile : skillFiles.stream()
                .sorted(Comparator.comparing(ArchiveSkillFile::path, String.CASE_INSENSITIVE_ORDER))
                .limit(limit)
                .toList()) {
            String skillPath = stripSkillFileSuffix(skillFile.path());
            SkillPreview preview = parsePreview(skillFile.markdown(), skillFile.path());
            discovered.put(skillPath, new RepositorySkillView(
                    preview.name(),
                    preview.description(),
                    command.repoUrl(),
                    skillFile.ref(),
                    skillPath,
                    normalizeSkillId(preview.name())));
        }

        return discovered.values().stream()
                .sorted(Comparator.comparing(RepositorySkillView::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private List<ArchiveSkillFile> discoverFromKnownRefs(GitHubRepository repo, String requestedRef) {
        List<String> refs = candidateRefs(repo, requestedRef);
        for (String ref : refs) {
            try {
                List<ArchiveSkillFile> files = discoverFromZip(repo, ref);
                if (!files.isEmpty()) {
                    return files;
                }
            } catch (RuntimeException ex) {
                if (!sanitizeQuery(requestedRef).isBlank()) {
                    throw ex;
                }
            }
        }
        return List.of();
    }

    private List<ArchiveSkillFile> discoverFromZip(GitHubRepository repo, String ref) {
        URI uri = buildUri(githubArchiveBase,
                "/" + repo.owner() + "/" + repo.name() + "/zip/" + encodePathSegment(ref));
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
                    if (isSkillMarkdown(path)) {
                        byte[] markdown = zip.readNBytes(128 * 1024);
                        skills.add(new ArchiveSkillFile(path, new String(markdown, StandardCharsets.UTF_8), ref));
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

    private SkillPreview parsePreview(String markdown, String skillFile) {
        String body = markdown == null ? "" : markdown;
        Map<String, Object> frontmatter = Map.of();
        if (body.startsWith("---")) {
            int end = body.indexOf("\n---", 3);
            if (end > 0) {
                try {
                    frontmatter = yamlMapper.readValue(body.substring(3, end), new TypeReference<Map<String, Object>>() {
                    });
                    body = body.substring(end + 4);
                } catch (IOException ignored) {
                    frontmatter = Map.of();
                }
            }
        }

        String name = text(frontmatter, "name");
        if (name == null || name.isBlank()) {
            name = text(frontmatter, "title");
        }
        if (name == null || name.isBlank()) {
            name = body.lines()
                    .map(String::trim)
                    .filter(line -> line.startsWith("# "))
                    .map(line -> line.substring(2).trim())
                    .findFirst()
                    .orElse(nameFromPath(skillFile));
        }

        String description = text(frontmatter, "description");
        if (description == null || description.isBlank()) {
            description = text(frontmatter, "summary");
        }
        if (description == null || description.isBlank()) {
            description = body.lines()
                    .map(String::trim)
                    .filter(line -> !line.isBlank()
                            && !line.startsWith("---")
                            && !line.startsWith("#")
                            && !line.startsWith("```"))
                    .findFirst()
                    .orElse("No description provided.");
        }
        return new SkillPreview(name.trim(), description.trim());
    }

    private List<SkillRepositoryView> searchGitHub(String query, int limit) {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        URI uri = buildUri(githubApiBase, "/search/repositories?q=" + encoded
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

    private List<String> searchQueries(String query) {
        String sanitized = sanitizeQuery(query);
        if (sanitized.isBlank()) {
            return DEFAULT_SEARCH_QUERIES;
        }
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        queries.add(sanitized + " SKILL.md");
        queries.add(sanitized + " skill");
        queries.add(sanitized + " agent skill");
        queries.add(sanitized + " SKILL.md agent");
        queries.add(sanitized + " topic:skills");
        queries.add(sanitized + " topic:agent-skills");
        queries.add(sanitized + " topic:codex-skills");
        queries.add(sanitized + " topic:claude-skills");
        queries.add(sanitized + " topic:skill-md");
        return List.copyOf(queries);
    }

    private List<String> candidateRefs(GitHubRepository repo, String requestedRef) {
        String ref = sanitizeQuery(requestedRef);
        if (!ref.isBlank()) {
            return List.of(ref);
        }
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        try {
            refs.add(defaultBranch(repo));
        } catch (IllegalStateException ignored) {
            // Fall through to the conventional refs below.
        }
        refs.add("main");
        refs.add("master");
        refs.add("trunk");
        refs.add("develop");
        return refs.stream().filter(value -> value != null && !value.isBlank()).toList();
    }

    private String defaultBranch(GitHubRepository repo) {
        URI uri = buildUri(githubApiBase, "/repos/" + encodePathSegment(repo.owner()) + "/" + encodePathSegment(repo.name()));
        JsonNode json = getJson(uri);
        String branch = json.path("default_branch").asText("");
        if (branch.isBlank()) {
            throw new IllegalStateException("GitHub repository did not report a default branch: " + repo.owner() + "/" + repo.name());
        }
        return branch;
    }

    private JsonNode getJson(URI uri) {
        HttpRequest request = request(uri).GET().build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("GitHub request failed: HTTP " + response.statusCode() + " for " + uri);
            }
            return objectMapper.readTree(response.body());
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
                .header("User-Agent", "cycbercompany");
    }

    private static URI buildUri(URI base, String path) {
        String prefix = base.toString().endsWith("/")
                ? base.toString().substring(0, base.toString().length() - 1)
                : base.toString();
        String suffix = path.startsWith("/") ? path : "/" + path;
        return URI.create(prefix + suffix);
    }

    private GitHubRepository parseGitHubRepository(String repoUrl) {
        Matcher matcher = GITHUB_URL.matcher(repoUrl == null ? "" : repoUrl.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Only GitHub repository URLs are supported.");
        }
        return new GitHubRepository(matcher.group(1), matcher.group(2).replaceAll("\\.git$", ""));
    }

    private static String sanitizeQuery(String value) {
        return value == null ? "" : value.trim().replace("\"", "");
    }

    private static String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static int clamp(Integer value, int min, int max, int fallback) {
        int resolved = value == null ? fallback : value;
        return Math.max(min, Math.min(max, resolved));
    }

    private static List<SkillRepositoryView> curatedFallback(String query, int limit) {
        String needle = sanitizeQuery(query).toLowerCase(Locale.ROOT);
        List<SkillRepositoryView> matches = CURATED.stream()
                .filter(entry -> needle.isBlank() || (entry.name() + " " + entry.description())
                        .toLowerCase(Locale.ROOT).contains(needle))
                .limit(limit)
                .toList();
        // An upstream rate limit should not turn the management page into an empty state,
        // even when the requested text has no match in the small local index.
        return matches.isEmpty() ? CURATED.stream().limit(limit).toList() : matches;
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
        if (isSkillMarkdown(path) && !path.contains("/")) {
            return "Repository Skill";
        }
        String[] parts = path.split("/");
        return parts.length >= 2 ? parts[parts.length - 2] : "Repository Skill";
    }

    private static String stripTopLevelDirectory(String zipEntryName) {
        String normalized = zipEntryName.replace('\\', '/');
        int slash = normalized.indexOf('/');
        if (slash < 0 || slash == normalized.length() - 1) {
            return null;
        }
        return normalized.substring(slash + 1);
    }

    private static boolean isSkillMarkdown(String path) {
        String normalized = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        return normalized.equals("skill.md") || normalized.endsWith("/skill.md");
    }

    private static String stripSkillFileSuffix(String skillFile) {
        String normalized = skillFile.replace('\\', '/');
        if (!isSkillMarkdown(normalized)) {
            return normalized;
        }
        int slash = normalized.lastIndexOf('/');
        return slash < 0 ? "" : normalized.substring(0, slash);
    }

    private static String text(Map<String, Object> fields, String key) {
        Object value = fields.get(key);
        return value instanceof String text ? text : null;
    }

    private static SkillRepositoryView seed(
            String id,
            String name,
            String description,
            String url,
            String defaultBranch,
            int stars,
            String sourceType) {
        return new SkillRepositoryView(id, name, description, url, defaultBranch, stars, sourceType);
    }

    private static SearchHit preferSearchHit(SearchHit current, SearchHit candidate) {
        if (candidate.queryRank() < current.queryRank()) {
            return candidate;
        }
        if (candidate.queryRank() == current.queryRank()
                && candidate.view().stars() > current.view().stars()) {
            return candidate;
        }
        return current;
    }

    private static HttpClient defaultHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    private record GitHubRepository(String owner, String name) {
    }

    private record SkillPreview(String name, String description) {
    }

    private record ArchiveSkillFile(String path, String markdown, String ref) {
    }

    private record SearchHit(SkillRepositoryView view, int queryRank) {
    }
}
