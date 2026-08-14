package io.github.yourname.cycbercompany.nodeclient.artifact;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yourname.cycbercompany.nodeclient.runtime.ToolExecutionResult;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** 把节点本地 Artifact 上传为服务端引用，并从 WebSocket 结果中移除本机路径。 */
public final class ArtifactUploadClient {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String endpoint;
    private final String nodeId;
    private final String nodeSecret;
    private final Path artifactRoot;

    public ArtifactUploadClient(
            ObjectMapper objectMapper,
            HttpClient httpClient,
            String serverUrl,
            String nodeId,
            String nodeSecret,
            Path artifactRoot) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.endpoint = trimTrailingSlash(serverUrl) + "/api/v1/node/artifacts";
        this.nodeId = nodeId;
        this.nodeSecret = nodeSecret;
        this.artifactRoot = artifactRoot.toAbsolutePath().normalize();
    }

    public ToolExecutionResult uploadIfPresent(String runId, ToolExecutionResult execution) {
        Object rawPath = execution.result() == null ? null : execution.result().get("artifactPath");
        if (!(rawPath instanceof String relativePath) || relativePath.isBlank()) return execution;
        Map<String, Object> safeResult = new LinkedHashMap<>(execution.result());
        safeResult.remove("artifactPath");
        try {
            Path artifact = artifactRoot.resolve(relativePath).normalize();
            if (!artifact.startsWith(artifactRoot)
                    || Files.isSymbolicLink(artifact)
                    || !Files.isRegularFile(artifact, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Generated Artifact path is outside the node Artifact root.");
            }
            String digest = fileDigest(artifact);
            String type = token(safeResult.get("artifactType"), "artifact");
            String mime = token(safeResult.get("mimeType"), "application/octet-stream");
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .header("X-CycberCompany-Node-Id", nodeId)
                    .header("Authorization", "Bearer " + nodeSecret)
                    .header("X-CycberCompany-Run-Id", runId == null ? "" : runId)
                    .header("X-CycberCompany-Artifact-Type", type)
                    .header("X-CycberCompany-Filename", artifact.getFileName().toString())
                    .header("X-CycberCompany-Artifact-Digest", digest)
                    .header("Content-Type", mime)
                    .POST(HttpRequest.BodyPublishers.ofFile(artifact))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("Artifact upload returned HTTP " + response.statusCode() + ".");
            }
            Map<String, Object> reference = objectMapper.readValue(
                    response.body(), new TypeReference<LinkedHashMap<String, Object>>() { });
            if (reference.get("id") == null || reference.get("digest") == null) {
                throw new IOException("Artifact upload response did not contain an immutable reference.");
            }
            safeResult.put("artifact", reference);
            Files.deleteIfExists(artifact);
            return new ToolExecutionResult(execution.success(), safeResult, execution.errorMessage());
        } catch (Exception ex) {
            safeResult.put("artifactUploadFailed", true);
            return ToolExecutionResult.failure(
                    safeResult,
                    "Artifact upload failed: " + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
        }
    }

    private static String fileDigest(Path file) throws IOException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (GeneralSecurityException ex) { throw new IllegalStateException("SHA-256 is unavailable.", ex); }
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
        }
        return "sha256:" + HexFormat.of().formatHex(digest.digest());
    }

    private static String token(Object value, String fallback) {
        String text = value == null ? "" : value.toString().trim();
        return text.isBlank() ? fallback : text;
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
