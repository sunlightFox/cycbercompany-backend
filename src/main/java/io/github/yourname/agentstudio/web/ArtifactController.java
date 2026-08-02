package io.github.yourname.agentstudio.web;

import io.github.yourname.agentstudio.artifact.ArtifactDownload;
import io.github.yourname.agentstudio.artifact.ArtifactService;
import io.github.yourname.agentstudio.node.NodeConnectionEntity;
import io.github.yourname.agentstudio.node.NodeService;
import io.github.yourname.agentstudio.security.CurrentActorProvider;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 节点上传大对象、用户按租户下载的 Artifact HTTP 边界。 */
@RestController
@RequestMapping("/api/v1")
public class ArtifactController {

    private static final String NODE_ID = "X-Agent-Studio-Node-Id";
    private static final String RUN_ID = "X-Agent-Studio-Run-Id";
    private static final String ARTIFACT_TYPE = "X-Agent-Studio-Artifact-Type";
    private static final String FILENAME = "X-Agent-Studio-Filename";
    private static final String DIGEST = "X-Agent-Studio-Artifact-Digest";

    private final NodeService nodes;
    private final ArtifactService artifacts;
    private final CurrentActorProvider actors;

    public ArtifactController(NodeService nodes, ArtifactService artifacts, CurrentActorProvider actors) {
        this.nodes = nodes;
        this.artifacts = artifacts;
        this.actors = actors;
    }

    @PostMapping("/node/artifacts")
    public Object upload(
            @RequestHeader(value = NODE_ID, required = false) String nodeId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = RUN_ID, required = false) String runId,
            @RequestHeader(value = ARTIFACT_TYPE, required = false) String artifactType,
            @RequestHeader(value = FILENAME, required = false) String filename,
            @RequestHeader(value = DIGEST, required = false) String digest,
            HttpServletRequest request) throws IOException {
        NodeConnectionEntity node = nodes.authenticateNode(nodeId, bearerSecret(authorization));
        return artifacts.store(
                node.tenantId(),
                node.id(),
                runId,
                artifactType,
                filename,
                request.getContentType(),
                digest,
                request.getContentLengthLong(),
                request.getInputStream());
    }

    @GetMapping("/artifacts/{id}")
    public ResponseEntity<Resource> download(@PathVariable String id, HttpServletRequest request) {
        ArtifactDownload download = artifacts.download(id, actors.current(request));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.artifact().mimeType()))
                .contentLength(download.artifact().sizeBytes())
                .eTag("\"" + download.artifact().digest() + "\"")
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(download.artifact().filename()).build().toString())
                .body(new FileSystemResource(download.path()));
    }

    private static String bearerSecret(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        String secret = authorization.substring(7).trim();
        return secret.isBlank() ? null : secret;
    }
}
