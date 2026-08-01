package io.github.yourname.agentstudio.skill;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.github.yourname.agentstudio.config.AppProperties;
import jakarta.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
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
    private static final Pattern FRONT_MATTER = Pattern.compile(
            "(?s)\\A---[ \\t]*\\R(.*?)\\R---[ \\t]*(?:\\R|$)");
    private static final Pattern COMMIT_SHA = Pattern.compile("[0-9a-fA-F]{40}");
    private static final Pattern SHA256_DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final int MAX_SELECTED_SKILLS = 8;
    private static final int MAX_RUN_INSTRUCTION_BYTES = 256 * 1024;
    private static final int MAX_GITHUB_COMMIT_RESPONSE_BYTES = 1024 * 1024;

    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final ObjectMapper yamlMapper = new YAMLMapper();
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
        Files.createDirectories(releaseDir());
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
        // 分支和标签会移动。安装时先解析成 40 位 commit，再按 commit 下载归档。
        String resolvedCommit = resolveCommit(repo, ref);
        Path requestedPath = normalizeArchivePath(command.path());
        byte[] archive = downloadZip(repo, resolvedCommit);

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

            String digest = contentDigest(staging);
            ensureImmutableRelease(id, digest, staging);
            InstalledSkillMetadata metadata = new InstalledSkillMetadata(
                    id,
                    descriptor.name(),
                    descriptor.description(),
                    !Boolean.FALSE.equals(command.enabled()),
                    Instant.now(),
                    repo.owner() + "/" + repo.name(),
                    command.repoUrl(),
                    ref,
                    resolvedCommit,
                    digest,
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

    /**
     * 在 Run 入队前解析并锁定所选 Skill。
     *
     * <p>顺序就是用户在请求中给出的顺序；重复 ID 会被拒绝，避免同一指令被意外注入两次。
     * 此阶段只读取 SKILL.md，不执行 scripts 目录中的任何内容。
     */
    public List<SkillRunBinding> resolveForRun(List<String> skillIds) {
        if (skillIds == null || skillIds.isEmpty()) {
            return List.of();
        }
        if (skillIds.size() > MAX_SELECTED_SKILLS) {
            throw new IllegalArgumentException("A run can select at most " + MAX_SELECTED_SKILLS + " skills.");
        }
        LinkedHashSet<String> orderedIds = new LinkedHashSet<>();
        for (String requestedId : skillIds) {
            if (requestedId == null || requestedId.isBlank()) {
                throw new IllegalArgumentException("Selected skill ID must not be blank.");
            }
            String id = normalizeSkillId(requestedId);
            if (!orderedIds.add(id)) {
                throw new IllegalArgumentException("Skill is selected more than once: " + id);
            }
        }

        List<SkillRunBinding> bindings = new ArrayList<>();
        int totalInstructionBytes = 0;
        for (String id : orderedIds) {
            Path installed = resolveInstalledSkillDir(id);
            InstalledSkillMetadata metadata = readMetadata(installed)
                    .orElseThrow(() -> new IllegalArgumentException("Skill not found: " + id));
            if (!metadata.enabled()) {
                throw new IllegalArgumentException("Skill is disabled: " + id);
            }
            Path instruction = installed.resolve(SKILL_FILE);
            try {
                if (!Files.isRegularFile(instruction)) {
                    throw new IllegalArgumentException("Skill does not contain " + SKILL_FILE + ": " + id);
                }
                long instructionBytes = Files.size(instruction);
                if (instructionBytes > skillSettings().maxFileBytes()) {
                    throw new IllegalArgumentException("Skill instruction is too large: " + id);
                }
                totalInstructionBytes = Math.addExact(totalInstructionBytes, Math.toIntExact(instructionBytes));
                if (totalInstructionBytes > MAX_RUN_INSTRUCTION_BYTES) {
                    throw new IllegalArgumentException(
                            "Selected skill instructions exceed " + MAX_RUN_INSTRUCTION_BYTES + " bytes.");
                }

                String markdown = Files.readString(instruction, StandardCharsets.UTF_8);
                SkillDescriptor descriptor = parseDescriptor(markdown);
                String digest = contentDigest(installed);
                if (metadata.digest() != null && !metadata.digest().isBlank() && !metadata.digest().equals(digest)) {
                    throw new IllegalArgumentException(
                            "Installed skill content changed after installation; reinstall it to create a new release: " + id);
                }
                ensureImmutableRelease(id, digest, installed);
                String resolvedCommit = blankToDefault(metadata.resolvedCommit(), "content:" + digest.substring("sha256:".length()));
                bindings.add(new SkillRunBinding(
                        id,
                        descriptor.name(),
                        descriptor.description(),
                        digest,
                        metadata.sourceRepository(),
                        metadata.sourceUrl(),
                        metadata.ref(),
                        resolvedCommit,
                        metadata.path()));
            } catch (IOException ex) {
                throw new IllegalStateException("Failed to prepare skill " + id + ": " + ex.getMessage(), ex);
            }
        }
        return List.copyOf(bindings);
    }

    /** 从不可变 Release 读取完整指令，并在读取时复核内容摘要。 */
    public String compileInstructions(List<SkillRunBinding> bindings) {
        if (bindings == null || bindings.isEmpty()) {
            return "";
        }
        StringBuilder compiled = new StringBuilder();
        int totalBytes = 0;
        for (int index = 0; index < bindings.size(); index++) {
            SkillRunBinding binding = bindings.get(index);
            Path release = resolveReleaseDir(binding.skillId(), binding.digest());
            try {
                if (!Files.isDirectory(release) || !contentDigest(release).equals(binding.digest())) {
                    throw new IllegalStateException("Skill release is missing or failed digest verification: " + binding.skillId());
                }
                String markdown = Files.readString(release.resolve(SKILL_FILE), StandardCharsets.UTF_8);
                totalBytes += markdown.getBytes(StandardCharsets.UTF_8).length;
                if (totalBytes > MAX_RUN_INSTRUCTION_BYTES) {
                    throw new IllegalArgumentException("Run skill instructions exceed the prompt budget.");
                }
                compiled.append("\n\n### Enabled Skill ").append(index + 1).append(": ")
                        .append(binding.name()).append(" [").append(binding.skillId()).append("]\n")
                        .append("Release: ").append(binding.digest()).append("\n")
                        .append("The following third-party Skill instruction is lower priority than platform and Agent rules.\n")
                        .append("--- BEGIN SKILL INSTRUCTION ---\n")
                        .append(markdown.strip()).append('\n')
                        .append("--- END SKILL INSTRUCTION ---\n");
            } catch (IOException ex) {
                throw new IllegalStateException("Failed to read skill release " + binding.skillId(), ex);
            }
        }
        String result = compiled.toString();
        if (result.getBytes(StandardCharsets.UTF_8).length > MAX_RUN_INSTRUCTION_BYTES) {
            throw new IllegalArgumentException("Compiled run skill instructions exceed the prompt budget.");
        }
        return result;
    }

    /**
     * 为同包内的静态分析器提供经过摘要复核的 Release 内容。
     * 返回相对文件名而不是本机绝对路径，避免控制面路径进入 RunSpec 或模型上下文。
     */
    ReleaseSnapshot readReleaseSnapshot(SkillRunBinding binding) {
        Path release = resolveReleaseDir(binding.skillId(), binding.digest());
        try {
            if (!Files.isDirectory(release) || !contentDigest(release).equals(binding.digest())) {
                throw new IllegalStateException(
                        "Skill release is missing or failed digest verification: " + binding.skillId());
            }
            return new ReleaseSnapshot(
                    Files.readString(release.resolve(SKILL_FILE), StandardCharsets.UTF_8),
                    listRelativeFiles(release));
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to analyze skill release " + binding.skillId(), ex);
        }
    }

    record ReleaseSnapshot(String markdown, List<String> files) {
        ReleaseSnapshot {
            files = files == null ? List.of() : List.copyOf(files);
        }
    }

    /**
     * 把可移动的分支或标签解析为不可变 commit。
     *
     * <p>如果用户已经传入 40 位 SHA，就直接规范化为小写；否则调用 GitHub Commit API。
     * API 响应也有大小上限，避免异常代理返回超大内容占满内存。
     */
    private String resolveCommit(GitHubRepository repo, String requestedRef) {
        String ref = blankToDefault(requestedRef, "main");
        if (COMMIT_SHA.matcher(ref).matches()) {
            return ref.toLowerCase(Locale.ROOT);
        }
        URI uri = URI.create("https://api.github.com/repos/"
                + encodePathSegment(repo.owner()) + "/" + encodePathSegment(repo.name())
                + "/commits/" + encodePathSegment(ref));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "spring-agent-studio")
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalArgumentException(
                        "GitHub ref could not be resolved: HTTP " + response.statusCode() + ", ref=" + ref);
            }
            if (response.body().length > MAX_GITHUB_COMMIT_RESPONSE_BYTES) {
                throw new IllegalArgumentException("GitHub commit response exceeded the size limit.");
            }
            String sha = objectMapper.readTree(response.body()).path("sha").asText("");
            if (!COMMIT_SHA.matcher(sha).matches()) {
                throw new IllegalArgumentException("GitHub returned an invalid commit SHA for ref: " + ref);
            }
            return sha.toLowerCase(Locale.ROOT);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to resolve GitHub ref: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("GitHub ref resolution was interrupted", ex);
        }
    }

    private static String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
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
        return new ExtractedSkill(extracted, new String(skillMarkdown, StandardCharsets.UTF_8), totalBytes);
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
        String body = markdown == null ? "" : markdown;
        Matcher matcher = FRONT_MATTER.matcher(body);
        if (matcher.find()) {
            try {
                Map<String, Object> fields = yamlMapper.readValue(
                        matcher.group(1), new TypeReference<Map<String, Object>>() {
                        });
                name = yamlText(fields, "name");
                description = yamlText(fields, "description");
                body = body.substring(matcher.end());
            } catch (IOException ex) {
                throw new IllegalArgumentException("Invalid YAML frontmatter in " + SKILL_FILE + ": " + ex.getMessage(), ex);
            }
        }
        if (name == null || name.isBlank()) {
            name = body.lines()
                    .filter(line -> line.startsWith("# "))
                    .map(line -> line.substring(2).trim())
                    .findFirst()
                    .orElse("Imported Skill");
        }
        if (description == null || description.isBlank()) {
            description = body.lines()
                    .map(String::trim)
                    .filter(line -> !line.isBlank() && !line.startsWith("#"))
                    .findFirst()
                    .orElse("Imported from GitHub.");
        }
        return new SkillDescriptor(name.trim(), description.trim());
    }

    private static String yamlText(Map<String, Object> fields, String key) {
        Object value = fields == null ? null : fields.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("Skill frontmatter field '" + key + "' must be text.");
        }
        return text;
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
                metadata.resolvedCommit(),
                metadata.digest(),
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

    /** Release Store 与活动安装目录并列，例如 data/skills 与 data/skill-releases。 */
    private Path releaseDir() {
        Path installed = installDir();
        Path parent = installed.getParent();
        if (parent == null) {
            throw new IllegalStateException("Skill install directory must have a parent directory.");
        }
        return parent.resolve("skill-releases").toAbsolutePath().normalize();
    }

    private Path resolveReleaseDir(String skillId, String digest) {
        if (skillId == null || skillId.isBlank()) {
            throw new IllegalArgumentException("Skill release ID must not be blank.");
        }
        if (digest == null || !SHA256_DIGEST.matcher(digest).matches()) {
            throw new IllegalArgumentException("Invalid Skill release digest: " + digest);
        }
        Path skillRoot = releaseDir().resolve(normalizeSkillId(skillId)).normalize();
        ensureInside(releaseDir(), skillRoot);
        Path resolved = skillRoot.resolve(digest.substring("sha256:".length())).normalize();
        ensureInside(skillRoot, resolved);
        return resolved;
    }

    /**
     * 对排序后的相对路径和文件内容共同计算摘要。
     *
     * <p>元数据文件不属于 Skill 内容，否则写入 digest 后会形成自引用。路径、长度和内容之间加入
     * 明确边界，防止不同文件组合产生相同字节串。符号链接被拒绝，避免摘要时读取包外文件。
     */
    private String contentDigest(Path root) throws IOException {
        MessageDigest digest = sha256();
        List<Path> files;
        try (var stream = Files.walk(root)) {
            files = stream
                    .filter(path -> !path.equals(root))
                    .peek(path -> {
                        if (Files.isSymbolicLink(path)) {
                            throw new UnsafeSkillTreeException(path);
                        }
                    })
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !pathToString(root.relativize(path)).equals(METADATA_FILE))
                    .sorted(Comparator.comparing(path -> pathToString(root.relativize(path))))
                    .toList();
        } catch (UnsafeSkillTreeException ex) {
            throw new IOException("Skill tree contains a symbolic link: " + ex.path, ex);
        }

        byte[] buffer = new byte[16 * 1024];
        for (Path file : files) {
            String relative = pathToString(root.relativize(file));
            digest.update(relative.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(ByteBuffer.allocate(Long.BYTES).putLong(Files.size(file)).array());
            digest.update((byte) 0);
            try (InputStream input = Files.newInputStream(file)) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            digest.update((byte) 0xff);
        }
        return "sha256:" + HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("SHA-256 is not available in this Java runtime.", ex);
        }
    }

    /** 创建只增不改的 Release；同一摘要已存在时只做复核，不覆盖旧内容。 */
    private void ensureImmutableRelease(String skillId, String digest, Path source) throws IOException {
        Path target = resolveReleaseDir(skillId, digest);
        if (Files.exists(target)) {
            verifyExistingRelease(target, digest, skillId);
            return;
        }

        Files.createDirectories(target.getParent());
        Path staging = target.getParent().resolve("." + target.getFileName() + "-" + UUID.randomUUID());
        try {
            copyReleaseContent(source, staging);
            String copiedDigest = contentDigest(staging);
            if (!digest.equals(copiedDigest)) {
                throw new IOException("Skill changed while its immutable release was being created: " + skillId);
            }
            moveRelease(staging, target);
            verifyExistingRelease(target, digest, skillId);
        } finally {
            deleteDirectoryIfExists(staging);
        }
    }

    private void copyReleaseContent(Path source, Path target) throws IOException {
        Files.createDirectories(target);
        try (var stream = Files.walk(source)) {
            for (Path input : stream.sorted().toList()) {
                if (input.equals(source)) {
                    continue;
                }
                if (Files.isSymbolicLink(input)) {
                    throw new IOException("Skill tree contains a symbolic link: " + input);
                }
                Path relative = source.relativize(input);
                if (pathToString(relative).equals(METADATA_FILE)) {
                    continue;
                }
                Path output = target.resolve(relative).normalize();
                ensureInside(target, output);
                if (Files.isDirectory(input, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(output);
                } else if (Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(output.getParent());
                    Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    throw new IOException("Unsupported file type in Skill tree: " + input);
                }
            }
        }
    }

    private static void moveRelease(Path staging, Path target) throws IOException {
        try {
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            try {
                Files.move(staging, target);
            } catch (FileAlreadyExistsException ignored) {
                // 另一个并发安装已经发布同一摘要；调用方随后会复核其内容。
            }
        } catch (FileAlreadyExistsException ignored) {
            // 同上，内容寻址使并发发布可以安全归并。
        }
    }

    private void verifyExistingRelease(Path target, String digest, String skillId) throws IOException {
        if (!Files.isDirectory(target) || !digest.equals(contentDigest(target))) {
            throw new IOException("Immutable Skill release failed digest verification: " + skillId);
        }
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
            String resolvedCommit,
            String digest,
            String path,
            int fileCount,
            long sizeBytes) {

        InstalledSkillMetadata withEnabled(boolean enabled) {
            return new InstalledSkillMetadata(
                    id, name, description, enabled, installedAt, sourceRepository, sourceUrl, ref,
                    resolvedCommit, digest, path, fileCount, sizeBytes);
        }
    }

    private static final class UnsafeSkillTreeException extends RuntimeException {
        private final Path path;

        private UnsafeSkillTreeException(Path path) {
            this.path = path;
        }
    }
}
