package io.github.yourname.agentstudio.nodeclient.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillBundleCacheTest {

    @TempDir
    Path temporaryDirectory;

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void downloadsVerifiesCachesAndMaterializesAnIndependentWritableCopy() throws Exception {
        Path release = temporaryDirectory.resolve("release");
        Files.createDirectories(release.resolve("references"));
        Files.writeString(release.resolve("SKILL.md"), "# Teaching Skill", StandardCharsets.UTF_8);
        Files.writeString(release.resolve("references/guide.md"), "immutable reference", StandardCharsets.UTF_8);
        String releaseDigest = SkillBundleCache.treeDigest(release);
        byte[] zip = deterministicZip(release);
        Path zipForDigest = temporaryDirectory.resolve("source.zip");
        Files.write(zipForDigest, zip);
        String bundleDigest = SkillBundleCache.fileDigest(zipForDigest);
        AtomicReference<String> nodeId = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        startServer(zip, releaseDigest, bundleDigest, nodeId, authorization);

        SkillBundleCache cache = new SkillBundleCache(
                HttpClient.newHttpClient(), serverUrl(), "node-1", "node-secret", temporaryDirectory.resolve("cache"));
        CachedSkillBundle cached = cache.ensure("teaching-skill", releaseDigest, bundleDigest);

        assertEquals("node-1", nodeId.get());
        assertEquals("Bearer node-secret", authorization.get());
        assertEquals("immutable reference", Files.readString(cached.contentRoot().resolve("references/guide.md")));
        assertTrue(Files.exists(cached.contentRoot().getParent().resolve("bundle.zip")));

        SkillWorkspaceManager workspaces = new SkillWorkspaceManager(temporaryDirectory.resolve("run-workspaces"));
        Path writable = workspaces.materialize("run-1", cached);
        Files.writeString(writable.resolve("references/guide.md"), "changed only inside this Run");
        assertEquals("immutable reference", Files.readString(cached.contentRoot().resolve("references/guide.md")));

        // 缓存命中仍要复核目录树，不能因为没有再次下载就信任被修改的文件。
        Path cachedGuide = cached.contentRoot().resolve("references/guide.md");
        cachedGuide.toFile().setWritable(true);
        Files.writeString(cachedGuide, "tampered cache");
        IllegalStateException tampered = assertThrows(
                IllegalStateException.class,
                () -> cache.ensure("teaching-skill", releaseDigest, bundleDigest));
        assertTrue(tampered.getMessage().contains("integrity verification"));
    }

    @Test
    void rejectsZipSlipBeforeWritingOutsideTheCache() throws Exception {
        byte[] malicious = zipWithEntry("../escaped.txt", "should never be written");
        Path archive = temporaryDirectory.resolve("malicious.zip");
        Files.write(archive, malicious);
        String bundleDigest = SkillBundleCache.fileDigest(archive);
        String releaseDigest = "sha256:" + "a".repeat(64);
        startServer(malicious, releaseDigest, bundleDigest, new AtomicReference<>(), new AtomicReference<>());
        SkillBundleCache cache = new SkillBundleCache(
                HttpClient.newHttpClient(), serverUrl(), "node-1", "secret", temporaryDirectory.resolve("cache"));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> cache.ensure("unsafe-skill", releaseDigest, bundleDigest));

        assertTrue(failure.getMessage().contains("Unsafe Skill bundle entry"));
        assertTrue(Files.notExists(temporaryDirectory.resolve("escaped.txt")));
    }

    private void startServer(
            byte[] body,
            String releaseDigest,
            String bundleDigest,
            AtomicReference<String> nodeId,
            AtomicReference<String> authorization) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/node/skill-bundles/teaching-skill/", exchange -> {
            nodeId.set(exchange.getRequestHeaders().getFirst("X-Agent-Studio-Node-Id"));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getResponseHeaders().set(SkillBundleCache.RELEASE_DIGEST_HEADER, releaseDigest);
            exchange.getResponseHeaders().set(SkillBundleCache.BUNDLE_DIGEST_HEADER, bundleDigest);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/api/v1/node/skill-bundles/unsafe-skill/", exchange -> {
            exchange.getResponseHeaders().set(SkillBundleCache.RELEASE_DIGEST_HEADER, releaseDigest);
            exchange.getResponseHeaders().set(SkillBundleCache.BUNDLE_DIGEST_HEADER, bundleDigest);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    private String serverUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static byte[] deterministicZip(Path root) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8);
                var stream = Files.walk(root)) {
            for (Path file : stream.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> root.relativize(path).toString().replace('\\', '/')))
                    .toList()) {
                byte[] bytes = Files.readAllBytes(file);
                CRC32 crc = new CRC32();
                crc.update(bytes);
                ZipEntry entry = new ZipEntry(root.relativize(file).toString().replace('\\', '/'));
                entry.setMethod(ZipEntry.STORED);
                entry.setTime(0L);
                entry.setSize(bytes.length);
                entry.setCompressedSize(bytes.length);
                entry.setCrc(crc.getValue());
                zip.putNextEntry(entry);
                zip.write(bytes);
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private static byte[] zipWithEntry(String name, String content) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return output.toByteArray();
    }
}
