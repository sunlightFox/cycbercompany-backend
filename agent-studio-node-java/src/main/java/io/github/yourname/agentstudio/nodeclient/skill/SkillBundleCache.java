package io.github.yourname.agentstudio.nodeclient.skill;

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
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 按 Release 摘要缓存 Skill Bundle，并在使用前完成完整性复核。
 *
 * <p>缓存目录不位于用户项目 workspace 下。普通文件工具看不到它，脚本也只会拿到复制后的 Run
 * workspace。这里的只读标记是额外防线，不把文件权限标记当作真正的进程沙箱。
 */
public final class SkillBundleCache {

    public static final String RELEASE_DIGEST_HEADER = "X-Agent-Studio-Release-Digest";
    public static final String BUNDLE_DIGEST_HEADER = "X-Agent-Studio-Bundle-Digest";
    private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Pattern SKILL_ID = Pattern.compile("[a-z0-9._-]{1,120}");
    private static final int MAX_FILES = 300;
    private static final int MAX_FILE_BYTES = 1024 * 1024;
    private static final long MAX_CONTENT_BYTES = 15L * 1024 * 1024;
    private static final long MAX_BUNDLE_BYTES = 20L * 1024 * 1024;

    private final HttpClient httpClient;
    private final String serverUrl;
    private final String nodeId;
    private final String nodeSecret;
    private final Path cacheRoot;

    public SkillBundleCache(
            HttpClient httpClient,
            String serverUrl,
            String nodeId,
            String nodeSecret,
            Path cacheRoot) {
        this.httpClient = httpClient;
        this.serverUrl = trimTrailingSlash(required(serverUrl, "serverUrl"));
        this.nodeId = required(nodeId, "nodeId");
        this.nodeSecret = required(nodeSecret, "nodeSecret");
        this.cacheRoot = cacheRoot.toAbsolutePath().normalize();
    }

    /** 下载不存在的缓存；已存在缓存只复核，篡改后直接失败而不是静默替换。 */
    public synchronized CachedSkillBundle ensure(
            String skillId,
            String releaseDigest,
            String expectedBundleDigest) {
        validateIdentity(skillId, releaseDigest, expectedBundleDigest);
        Path target = cachePath(skillId, releaseDigest);
        try {
            if (Files.exists(target)) {
                return verifyCached(skillId, releaseDigest, expectedBundleDigest, target);
            }
            Files.createDirectories(target.getParent());
            Path staging = target.getParent().resolve("." + target.getFileName() + "-" + UUID.randomUUID());
            try {
                Files.createDirectories(staging);
                Path archive = staging.resolve("bundle.zip");
                download(skillId, releaseDigest, expectedBundleDigest, archive);
                if (!expectedBundleDigest.equals(fileDigest(archive))) {
                    throw new IOException("Downloaded Skill bundle failed ZIP digest verification.");
                }
                Path content = staging.resolve("content");
                extract(archive, content);
                if (!releaseDigest.equals(treeDigest(content))) {
                    throw new IOException("Downloaded Skill bundle failed release tree digest verification.");
                }
                markTreeReadOnly(content);
                moveDirectory(staging, target);
            } finally {
                deleteDirectoryIfExists(staging);
            }
            return verifyCached(skillId, releaseDigest, expectedBundleDigest, target);
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Unable to cache Skill bundle " + skillId + ": " + ex.getMessage(), ex);
        }
    }

    private CachedSkillBundle verifyCached(
            String skillId,
            String releaseDigest,
            String expectedBundleDigest,
            Path target) throws IOException {
        Path archive = target.resolve("bundle.zip");
        Path content = target.resolve("content");
        if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)
                || !Files.isDirectory(content, LinkOption.NOFOLLOW_LINKS)
                || !expectedBundleDigest.equals(fileDigest(archive))
                || !releaseDigest.equals(treeDigest(content))) {
            throw new IOException("Cached Skill bundle failed integrity verification; remove it explicitly before retrying.");
        }
        return new CachedSkillBundle(skillId, releaseDigest, expectedBundleDigest, content);
    }

    private void download(
            String skillId,
            String releaseDigest,
            String expectedBundleDigest,
            Path output) throws IOException, InterruptedException {
        String releaseHex = releaseDigest.substring("sha256:".length());
        URI uri = URI.create(serverUrl + "/api/v1/node/skill-bundles/" + encode(skillId) + "/" + releaseHex);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("X-Agent-Studio-Node-Id", nodeId)
                .header("Authorization", "Bearer " + nodeSecret)
                .GET()
                .build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream input = response.body()) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("Skill bundle download returned HTTP " + response.statusCode() + ".");
            }
            String actualRelease = response.headers().firstValue(RELEASE_DIGEST_HEADER).orElse("");
            String actualBundle = response.headers().firstValue(BUNDLE_DIGEST_HEADER).orElse("");
            if (!releaseDigest.equals(actualRelease) || !expectedBundleDigest.equals(actualBundle)) {
                throw new IOException("Skill bundle response metadata did not match the requested immutable release.");
            }
            long declaredLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (declaredLength > MAX_BUNDLE_BYTES) {
                throw new IOException("Skill bundle exceeds the download size limit.");
            }
            copyBounded(input, output, MAX_BUNDLE_BYTES);
        }
    }

    private static void extract(Path archive, Path contentRoot) throws IOException {
        Files.createDirectories(contentRoot);
        Set<String> seen = new HashSet<>();
        int files = 0;
        long totalBytes = 0;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                if (++files > MAX_FILES) {
                    throw new IOException("Skill bundle contains too many files.");
                }
                String name = entry.getName().replace('\\', '/');
                Path relative = Path.of(name).normalize();
                if (name.isBlank() || relative.isAbsolute() || relative.startsWith("..") || name.contains(":")) {
                    throw new IOException("Unsafe Skill bundle entry: " + entry.getName());
                }
                String normalized = relative.toString().replace('\\', '/');
                if (!seen.add(normalized)) {
                    throw new IOException("Duplicate Skill bundle entry: " + normalized);
                }
                byte[] bytes = zip.readNBytes(MAX_FILE_BYTES + 1);
                if (bytes.length > MAX_FILE_BYTES) {
                    throw new IOException("Skill bundle entry exceeds the per-file limit: " + normalized);
                }
                totalBytes += bytes.length;
                if (totalBytes > MAX_CONTENT_BYTES) {
                    throw new IOException("Skill bundle exceeds the extracted size limit.");
                }
                Path output = contentRoot.resolve(relative).normalize();
                ensureInside(contentRoot, output);
                Files.createDirectories(output.getParent());
                Files.write(output, bytes);
            }
        }
        if (files == 0 || !Files.isRegularFile(contentRoot.resolve("SKILL.md"), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Skill bundle does not contain SKILL.md.");
        }
    }

    /** 与控制面 SkillCatalog 使用相同的路径、长度、内容编码。 */
    static String treeDigest(Path root) throws IOException {
        MessageDigest digest = sha256();
        List<Path> files;
        try (var stream = Files.walk(root)) {
            files = stream
                    .filter(path -> !path.equals(root))
                    .peek(path -> {
                        if (Files.isSymbolicLink(path)) {
                            throw new UnsafeCacheTreeException(path);
                        }
                    })
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .sorted(Comparator.comparing(path -> relative(root, path)))
                    .toList();
        } catch (UnsafeCacheTreeException ex) {
            throw new IOException("Skill cache contains a symbolic link: " + ex.path, ex);
        }
        byte[] buffer = new byte[16 * 1024];
        for (Path file : files) {
            String relative = relative(root, file);
            digest.update(relative.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(ByteBuffer.allocate(Long.BYTES).putLong(Files.size(file)).array());
            digest.update((byte) 0);
            try (InputStream input = Files.newInputStream(file)) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) digest.update(buffer, 0, read);
                }
            }
            digest.update((byte) 0xff);
        }
        return "sha256:" + HexFormat.of().formatHex(digest.digest());
    }

    static String fileDigest(Path file) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return "sha256:" + HexFormat.of().formatHex(digest.digest());
    }

    private Path cachePath(String skillId, String releaseDigest) {
        Path skillRoot = cacheRoot.resolve(skillId).normalize();
        ensureInside(cacheRoot, skillRoot);
        Path result = skillRoot.resolve(releaseDigest.substring("sha256:".length())).normalize();
        ensureInside(skillRoot, result);
        return result;
    }

    private static void copyBounded(InputStream input, Path output, long maxBytes) throws IOException {
        long total = 0;
        byte[] buffer = new byte[16 * 1024];
        try (var target = Files.newOutputStream(output)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                total += read;
                if (total > maxBytes) {
                    throw new IOException("Skill bundle exceeds the download size limit.");
                }
                target.write(buffer, 0, read);
            }
        }
    }

    private static void markTreeReadOnly(Path root) throws IOException {
        try (var stream = Files.walk(root)) {
            // Windows 的只读属性对目录没有一致语义，File.setReadOnly() 也可能直接返回 false。
            // 内容文件必须只读；目录则依靠节点私有路径、工具不可见性和每次摘要复核共同保护。
            for (Path path : stream.filter(Files::isRegularFile).toList()) {
                if (!path.toFile().setReadOnly()) {
                    throw new IOException("Unable to mark Skill cache read-only: " + relative(root, path));
                }
            }
        }
    }

    private static void moveDirectory(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            try {
                Files.move(source, target);
            } catch (FileAlreadyExistsException ignored) {
                // 并发下载同一摘要时复用先完成的缓存，调用方随后仍会完整复核。
            }
        } catch (FileAlreadyExistsException ignored) {
            // 同上。
        }
    }

    private static void deleteDirectoryIfExists(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                path.toFile().setWritable(true);
                Files.deleteIfExists(path);
            }
        }
    }

    private static void validateIdentity(String skillId, String releaseDigest, String bundleDigest) {
        if (skillId == null || !SKILL_ID.matcher(skillId).matches()) {
            throw new IllegalArgumentException("Invalid Skill ID for bundle download.");
        }
        if (releaseDigest == null || !DIGEST.matcher(releaseDigest).matches()) {
            throw new IllegalArgumentException("Invalid Skill release digest.");
        }
        if (bundleDigest == null || !DIGEST.matcher(bundleDigest).matches()) {
            throw new IllegalArgumentException("Invalid Skill bundle digest.");
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("SHA-256 is unavailable.", ex);
        }
    }

    private static String relative(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    private static void ensureInside(Path root, Path child) {
        if (!child.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("Resolved Skill path escaped its managed root.");
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank.");
        return value.trim();
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static final class UnsafeCacheTreeException extends RuntimeException {
        private final Path path;

        private UnsafeCacheTreeException(Path path) {
            this.path = path;
        }
    }
}
