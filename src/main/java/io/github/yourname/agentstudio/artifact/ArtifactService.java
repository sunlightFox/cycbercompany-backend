package io.github.yourname.agentstudio.artifact;

import io.github.yourname.agentstudio.config.AppProperties;
import io.github.yourname.agentstudio.security.ActorContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.List;
import java.nio.charset.StandardCharsets;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 本地优先的 Artifact 元数据与文件存储；以后可在不改变 API 的前提下替换对象存储。 */
@Service
public class ArtifactService {

    private static final long MAX_ARTIFACT_BYTES = 50L * 1024 * 1024;
    private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");
    private final ArtifactRepository repository;
    private final Path storageRoot;

    public ArtifactService(ArtifactRepository repository, AppProperties properties) {
        this.repository = repository;
        Path data = properties.dataDir() == null ? Path.of("./data") : properties.dataDir();
        this.storageRoot = data.resolve("artifacts").toAbsolutePath().normalize();
    }

    /** Stores a server-generated artifact (for example a report assembled by a backend tool). */
    @Transactional
    public ArtifactView createGenerated(String tenantId, String runId, String artifactType,
            String filename, String mimeType, byte[] content) {
        if (content == null) throw new IllegalArgumentException("Artifact content is required.");
        return store(tenantId, "backend", runId, artifactType, filename, mimeType,
                digest(content), content.length, new java.io.ByteArrayInputStream(content));
    }

    public ArtifactView createText(String tenantId, String runId, String artifactType,
            String filename, String mimeType, String content) {
        return createGenerated(tenantId, runId, artifactType, filename, mimeType,
                (content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
    }

    public ArtifactView createDocx(String tenantId, String runId, String filename, String title, String content) {
        try (XWPFDocument document = new XWPFDocument(); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            if (title != null && !title.isBlank()) document.createParagraph().createRun().setText(title);
            for (String line : (content == null ? "" : content).split("\\R", -1)) {
                document.createParagraph().createRun().setText(line);
            }
            document.write(out);
            return createGenerated(tenantId, runId, "document", filename, "application/vnd.openxmlformats-officedocument.wordprocessingml.document", out.toByteArray());
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to generate DOCX artifact.", ex);
        }
    }

    public ArtifactView createXlsx(String tenantId, String runId, String filename, String title, String content) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Report");
            int row = 0;
            if (title != null && !title.isBlank()) sheet.createRow(row++).createCell(0).setCellValue(title);
            for (String line : (content == null ? "" : content).split("\\R", -1)) {
                var cells = line.split("\\t", -1);
                var excelRow = sheet.createRow(row++);
                for (int column = 0; column < cells.length; column++) excelRow.createCell(column).setCellValue(cells[column]);
            }
            for (int column = 0; column < 12; column++) sheet.autoSizeColumn(column);
            workbook.write(out);
            return createGenerated(tenantId, runId, "spreadsheet", filename,
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to generate XLSX artifact.", ex);
        }
    }

    @Transactional
    public ArtifactView store(
            String tenantId,
            String nodeId,
            String runId,
            String artifactType,
            String filename,
            String mimeType,
            String expectedDigest,
            long declaredLength,
            InputStream input) {
        if (declaredLength > MAX_ARTIFACT_BYTES) throw new IllegalArgumentException("Artifact exceeds the 50 MB limit.");
        if (expectedDigest == null || !DIGEST.matcher(expectedDigest).matches()) {
            throw new IllegalArgumentException("Artifact SHA-256 digest is required.");
        }
        String safeFilename = safeFilename(filename);
        String id = "art_" + UUID.randomUUID();
        Path directory = storageRoot.resolve(id).normalize();
        Path target = directory.resolve(safeFilename).normalize();
        ensureInside(directory, target);
        Path staging = storageRoot.resolve("." + id + ".part").normalize();
        try {
            Files.createDirectories(storageRoot);
            StoredFile stored = copyAndDigest(input, staging);
            if (declaredLength >= 0 && declaredLength != stored.sizeBytes()) {
                throw new IllegalArgumentException("Artifact Content-Length did not match uploaded bytes.");
            }
            if (!expectedDigest.equals(stored.digest())) {
                throw new IllegalArgumentException("Artifact upload failed SHA-256 verification.");
            }
            Files.createDirectories(directory);
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            ArtifactEntity entity = repository.save(new ArtifactEntity(
                    id,
                    required(tenantId, "tenantId"),
                    required(nodeId, "nodeId"),
                    blankToNull(runId),
                    safeToken(artifactType, "artifact"),
                    safeFilename,
                    mimeType == null || mimeType.isBlank() ? "application/octet-stream" : mimeType,
                    stored.sizeBytes(),
                    stored.digest(),
                    storageRoot.relativize(target).toString().replace('\\', '/'),
                    Instant.now()));
            return ArtifactView.from(entity);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to store Artifact: " + ex.getMessage(), ex);
        } finally {
            try { Files.deleteIfExists(staging); } catch (IOException ignored) { }
        }
    }

    @Transactional(readOnly = true)
    public ArtifactDownload download(String id, ActorContext actor) {
        ArtifactEntity entity = repository.findByIdAndTenantId(id, actor.tenantId())
                .orElseThrow(() -> new IllegalArgumentException("Artifact not found: " + id));
        Path path = storageRoot.resolve(entity.storagePath()).normalize();
        ensureInside(storageRoot, path);
        if (!Files.isRegularFile(path)) throw new IllegalStateException("Artifact content is missing: " + id);
        return new ArtifactDownload(ArtifactView.from(entity), path);
    }

    @Transactional(readOnly = true)
    public List<ArtifactView> listRunArtifacts(String runId, ActorContext actor) {
        if (runId == null || runId.isBlank()) return List.of();
        return repository.findByRunIdAndTenantIdOrderByCreatedAtAsc(runId, actor.tenantId())
                .stream().map(ArtifactView::from).toList();
    }

    private static StoredFile copyAndDigest(InputStream input, Path output) throws IOException {
        MessageDigest digest = sha256();
        long total = 0;
        byte[] buffer = new byte[16 * 1024];
        try (var target = Files.newOutputStream(output)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                total += read;
                if (total > MAX_ARTIFACT_BYTES) throw new IllegalArgumentException("Artifact exceeds the 50 MB limit.");
                digest.update(buffer, 0, read);
                target.write(buffer, 0, read);
            }
        }
        return new StoredFile(total, "sha256:" + HexFormat.of().formatHex(digest.digest()));
    }

    private static String safeFilename(String value) {
        String name = value == null ? "artifact.bin" : Path.of(value).getFileName().toString();
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (name.isBlank() || ".".equals(name) || "..".equals(name)) name = "artifact.bin";
        return name.length() <= 180 ? name : name.substring(name.length() - 180);
    }

    private static String safeToken(String value, String fallback) {
        String safe = value == null ? "" : value.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.isBlank() ? fallback : safe.substring(0, Math.min(80, safe.length()));
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank.");
        return value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void ensureInside(Path root, Path child) {
        if (!child.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("Artifact path escaped the managed store.");
        }
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (GeneralSecurityException ex) { throw new IllegalStateException("SHA-256 is unavailable.", ex); }
    }

    private static String digest(byte[] bytes) {
        MessageDigest digest = sha256();
        digest.update(bytes);
        return "sha256:" + HexFormat.of().formatHex(digest.digest());
    }

    private record StoredFile(long sizeBytes, String digest) {
    }
}
