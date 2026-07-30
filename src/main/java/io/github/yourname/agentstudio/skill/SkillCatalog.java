package io.github.yourname.agentstudio.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yourname.agentstudio.config.AppProperties;
import jakarta.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.springframework.stereotype.Service;

/**
 * Manages local Agent/Codex skills.
 *
 * <p>A skill is intentionally treated as data in this version: a directory
 * containing a required {@code SKILL.md} file plus optional references/scripts.
 * This service can install and index those files, but it never executes
 * downloaded scripts. Keeping "download" and "execute" separate makes the
 * security model easy to review while the project is still evolving.
 */
@Service
public class SkillCatalog {

    private static final String METADATA_FILE = ".agent-studio-skill.json";
    private static final String SKILL_FILE = "SKILL.md";
    private static final Pattern GITHUB_URL = Pattern.compile(
            "https?://github\\.com/([^/]+)/([^/#?]+)(?:/.*)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern FRONT_MATTER_FIELD = Pattern.compile(
            "(?m)^([A-Za-z][A-Za-z0-9_-]*)\\s*:\\s*(.+)$");

    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public SkillCatalog(AppProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(12))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @PostConstruct
    void ensureInstallDirectoryExists() throws IOException {
        Files.createDirectories(installDir());
    }

    /**
     * Lists skills currently installed on this backend node.
     *
     * <p>The directory is the source of truth so users can inspect or back up
     * the installed skills without understanding the database schema.
     */
    public List<SkillView> list() {
        try {
            if (!Files.exists(installDir())) {
                return List.of();
            }
            try (var stream = Files.list(installDir())) {
                return stream
                        .filter(Files::isDirectory)
                        .map(this::readInstalledSkill)
                        .flatMap(Optional::stream)
                        .sorted(Comparator.comparing(SkillView::name, String.CASE_INSENSITIVE_ORDER))
                        .toList();
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to list installed skills: " + ex.getMessage(), ex);
        }
    }

    public SkillDetailView get(String id) {
        Path skillDir = resolveInstalledSkillDir(id);
        Path skillFile = skillDir.resolve(SKILL_FILE);
        if (!Files.exists(skillFile)) {
            throw new IllegalArgumentException("Skill not found: " + id);
        }
        try {
            SkillView summary = readInstalledSkill(skillDir)
                    .orElseThrow(() -> new IllegalArgumentException("Skill metadata is unreadable: " + id));
            return new SkillDetailView(summary, Files.readString(skillFile), listRelativeFiles(skillDir));
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read skill " + id + ": " + ex.getMessage(), ex);
        }
    }

    /**
     * Installs one skill from a public GitHub repository.
     *
     * <p>The command can point to the repository root or to a subdirectory. If
     * the path is omitted, the archive must contain exactly one {@code SKILL.md}
     * candidate; otherwise the caller should choose a specific path from the
     * repository discovery API.
     */
    public SkillView install(InstallSkillCommand command) {
        GitHubRepository repo = parseGitHubRepository(command.repoUrl());
        String ref = blankToDefault(command.ref(), "main");
        Path requestedPath = normalizeArchivePath(command.path());
        byte[] archive = downloadZip(repo, ref);

        try {
            ExtractedSkill extracted = extractSkill(archive, requestedPath);
            SkillDescriptor descriptor = parseDescriptor(extracted.skillMarkdown());
            String id = normalizeSkillId(blankToDefault(command.id(), descriptor.name()));
            Path target = resolveInstalledSkillDir(id);
            if (Files.exists(target) && !Boolean.TRUE.equals(command.overwrite())) {
                throw new IllegalArgumentException("Skill already installed: " + id + ". Pass overwrite=true to replace it.");
            }

            Path staging = installDir().resolve("." + id + "-" + UUID.randomUUID());
            deleteDirectoryIfExists(staging);
            Files.createDirectories(staging);
            for (ExtractedFile file : extracted.files()) {
                Path output = staging.resolve(file.relativePath()).normalize();
                ensureInside(staging, output);
                Files.createDirectories(output.getParent());
                Files.copy(new ByteArrayInputStream(file.content()), output, StandardCopyOption.REPLACE_EXISTING);
            }

            InstalledSkillMetadata metadata = new InstalledSkillMetadata(
                    id,
                    descriptor.name(),
                    descriptor.description(),
                    !Boolean.FALSE.equals(command.enabled()),
                    Instant.now(),
                    repo.owner() + "/" + repo.name(),
                    command.repoUrl(),
                    ref,
                    pathToString(requestedPath),
                    extracted.files().size(),
                    extracted.totalBytes());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(staging.resolve(METADATA_FILE).toFile(), metadata);

            deleteDirectoryIfExists(target);
            Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
            return toView(metadata);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to install skill: " + ex.getMessage(), ex);
        }
    }

    public SkillView setEnabled(String id, UpdateSkillCommand command) {
        Path skillDir = resolveInstalledSkillDir(id);
        InstalledSkillMetadata metadata = readMetadata(skillDir)
                .orElseThrow(() -> new IllegalArgumentException("Skill not found: " + id));
        InstalledSkillMetadata updated = metadata.withEnabled(command.enabled());
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(skillDir.resolve(METADATA_FILE).toFile(), updated);
            return toView(updated);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to update skill " + id + ": " + ex.getMessage(), ex);
        }
    }

    public void uninstall(String id) {
        Path skillDir = resolveInstalledSkillDir(id);
        if (!Files.exists(skillDir)) {
            throw new IllegalArgumentException("Skill not found: " + id);
        }
        try {
            deleteDirectoryIfExists(skillDir);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to uninstall skill " + id + ": " + ex.getMessage(), ex);
        }
    }

    private byte[] downloadZip(GitHubRepository repo, String ref) {
        URI uri = URI.create("https://codeload.github.com/" + repo.owner() + "/" + repo.name() + "/zip/" + ref);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "spring-agent-studio")
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalArgumentException("GitHub archive download failed: HTTP " + response.statusCode());
            }
            int maxArchiveBytes = skillSettings().maxArchiveBytes();
            if (response.body().length > maxArchiveBytes) {
                throw new IllegalArgumentException("GitHub archive is too large. Limit=" + maxArchiveBytes + " bytes");
            }
            return response.body();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to download GitHub archive: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("GitHub archive download was interrupted", ex);
        }
    }

    private ExtractedSkill extractSkill(byte[] archive, Path requestedPath) throws IOException {
        List<ArchiveFile> archiveFiles = readArchiveFiles(archive, requestedPath);
        Path skillRoot = requestedPath == null ? inferSkillRoot(archiveFiles) : requestedPath;
        List<ExtractedFile> extracted = new ArrayList<>();
        byte[] skillMarkdown = null;
        long totalBytes = 0;

        for (ArchiveFile file : archiveFiles) {
            Path relative = file.path();
            if (!relative.startsWith(skillRoot)) {
                continue;
            }
            Path insideSkill = skillRoot.relativize(relative);
            if (insideSkill.toString().isBlank()) {
                continue;
            }
            extracted.add(new ExtractedFile(insideSkill, file.content()));
            totalBytes += file.content().length;
            if (insideSkill.toString().replace('\\', '/').equals(SKILL_FILE)) {
                skillMarkdown = file.content();
            }
        }

        if (skillMarkdown == null) {
            throw new IllegalArgumentException("Selected path does not contain " + SKILL_FILE + ": " + pathToString(skillRoot));
        }
        return new ExtractedSkill(extracted, new String(skillMarkdown), totalBytes);
    }

    private List<ArchiveFile> readArchiveFiles(byte[] archive, Path requestedPath) throws IOException {
        List<ArchiveFile> files = new ArrayList<>();
        AppProperties.SkillStore settings = skillSettings();
        try (InputStream input = new ByteArrayInputStream(archive);
                ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                if (files.size() >= settings.maxFiles()) {
                    throw new IllegalArgumentException("Skill archive contains too many files. Limit=" + settings.maxFiles());
                }
                Path path = stripTopLevelDirectory(entry.getName());
                if (path == null || isUnsafeRelativePath(path)) {
                    throw new IllegalArgumentException("Unsafe archive entry path: " + entry.getName());
                }
                if (requestedPath != null && !path.startsWith(requestedPath)) {
                    continue;
                }
                byte[] content = zip.readNBytes(settings.maxFileBytes() + 1);
                if (content.length > settings.maxFileBytes()) {
                    throw new IllegalArgumentException("Archive entry is too large: " + entry.getName());
                }
                files.add(new ArchiveFile(path, content));
            }
        }
        return files;
    }

    private Path inferSkillRoot(List<ArchiveFile> files) {
        List<Path> roots = files.stream()
                .map(ArchiveFile::path)
                .filter(path -> path.getFileName().toString().equals(SKILL_FILE))
                .map(Path::getParent)
                .map(path -> path == null ? Path.of("") : path)
                .toList();
        if (roots.isEmpty()) {
            throw new IllegalArgumentException("Repository archive does not contain any " + SKILL_FILE);
        }
        if (roots.size() > 1) {
            String choices = roots.stream().map(this::pathToString).limit(20).toList().toString();
            throw new IllegalArgumentException("Repository contains multiple skills; pass path explicitly. Candidates=" + choices);
        }
        return roots.getFirst();
    }

    private SkillDescriptor parseDescriptor(String markdown) {
        String name = null;
        String description = null;
        if (markdown.startsWith("---")) {
            int end = markdown.indexOf("\n---", 3);
            if (end > 0) {
                String frontMatter = markdown.substring(3, end);
                Matcher matcher = FRONT_MATTER_FIELD.matcher(frontMatter);
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
                    .orElse("Imported Skill");
        }
        if (description == null || description.isBlank()) {
            description = markdown.lines()
                    .map(String::trim)
                    .filter(line -> !line.isBlank() && !line.startsWith("---") && !line.startsWith("#"))
                    .findFirst()
                    .orElse("Imported from GitHub.");
        }
        return new SkillDescriptor(name, description);
    }

    private Optional<SkillView> readInstalledSkill(Path skillDir) {
        return readMetadata(skillDir).map(this::toView);
    }

    private Optional<InstalledSkillMetadata> readMetadata(Path skillDir) {
        Path metadataFile = skillDir.resolve(METADATA_FILE);
        if (!Files.exists(metadataFile)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(metadataFile.toFile(), InstalledSkillMetadata.class));
        } catch (IOException ex) {
            return Optional.empty();
        }
    }

    private List<String> listRelativeFiles(Path skillDir) throws IOException {
        try (var stream = Files.walk(skillDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(skillDir::relativize)
                    .map(this::pathToString)
                    .filter(name -> !name.equals(METADATA_FILE))
                    .sorted()
                    .toList();
        }
    }

    private SkillView toView(InstalledSkillMetadata metadata) {
        return new SkillView(
                metadata.id(),
                metadata.name(),
                metadata.description(),
                metadata.enabled(),
                metadata.installedAt(),
                metadata.sourceRepository(),
                metadata.sourceUrl(),
                metadata.ref(),
                metadata.path(),
                metadata.fileCount(),
                metadata.sizeBytes());
    }

    private Path resolveInstalledSkillDir(String id) {
        String safeId = normalizeSkillId(id);
        Path resolved = installDir().resolve(safeId).normalize();
        ensureInside(installDir(), resolved);
        return resolved;
    }

    private Path installDir() {
        AppProperties.SkillStore settings = properties.skills();
        if (settings != null && settings.installDir() != null) {
            return settings.installDir().toAbsolutePath().normalize();
        }
        Path dataDir = properties.dataDir() == null ? Path.of("./data") : properties.dataDir();
        return dataDir.resolve("skills").toAbsolutePath().normalize();
    }

    private AppProperties.SkillStore skillSettings() {
        AppProperties.SkillStore settings = properties.skills();
        if (settings == null) {
            return new AppProperties.SkillStore(installDir(), 15 * 1024 * 1024, 300, 1024 * 1024);
        }
        return settings;
    }

    private GitHubRepository parseGitHubRepository(String repoUrl) {
        Matcher matcher = GITHUB_URL.matcher(repoUrl == null ? "" : repoUrl.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Only public GitHub repository URLs are supported, e.g. https://github.com/owner/repo");
        }
        return new GitHubRepository(matcher.group(1), matcher.group(2).replaceAll("\\.git$", ""));
    }

    private static Path stripTopLevelDirectory(String zipEntryName) {
        String normalized = zipEntryName.replace('\\', '/');
        int slash = normalized.indexOf('/');
        if (slash < 0 || slash == normalized.length() - 1) {
            return null;
        }
        return Path.of(normalized.substring(slash + 1)).normalize();
    }

    private static Path normalizeArchivePath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        Path normalized = Path.of(path.replace('\\', '/')).normalize();
        if (isUnsafeRelativePath(normalized)) {
            throw new IllegalArgumentException("Unsafe skill path: " + path);
        }
        return normalized;
    }

    private static boolean isUnsafeRelativePath(Path path) {
        return path.isAbsolute() || path.toString().contains("..");
    }

    private static void ensureInside(Path root, Path child) {
        if (!child.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("Resolved path escapes the skill install directory");
        }
    }

    private static String normalizeSkillId(String value) {
        String id = blankToDefault(value, "skill")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("^-+|-+$", "");
        if (id.isBlank()) {
            id = "skill";
        }
        return id;
    }

    private static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static String stripQuotes(String value) {
        return value.replaceAll("^['\"]|['\"]$", "");
    }

    private String pathToString(Path path) {
        return path == null ? "" : path.toString().replace('\\', '/');
    }

    private static void deleteDirectoryIfExists(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private record GitHubRepository(String owner, String name) {
    }

    private record ArchiveFile(Path path, byte[] content) {
    }

    private record ExtractedFile(Path relativePath, byte[] content) {
    }

    private record ExtractedSkill(List<ExtractedFile> files, String skillMarkdown, long totalBytes) {
    }

    private record SkillDescriptor(String name, String description) {
    }

    private record InstalledSkillMetadata(
            String id,
            String name,
            String description,
            boolean enabled,
            Instant installedAt,
            String sourceRepository,
            String sourceUrl,
            String ref,
            String path,
            int fileCount,
            long sizeBytes) {

        InstalledSkillMetadata withEnabled(boolean enabled) {
            return new InstalledSkillMetadata(
                    id, name, description, enabled, installedAt, sourceRepository, sourceUrl, ref, path, fileCount, sizeBytes);
        }
    }
}
