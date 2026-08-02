package io.github.yourname.agentstudio.web;

import io.github.yourname.agentstudio.node.NodeService;
import io.github.yourname.agentstudio.skill.SkillBundleDownload;
import io.github.yourname.agentstudio.skill.SkillCatalog;
import java.time.Duration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 节点专用的 Skill Bundle 下载入口。
 *
 * <p>大对象通过普通 HTTP 流式下载，WebSocket 只传摘要和下载地址。这个接口虽然在 Spring Security
 * 层允许进入，但并不是匿名接口：节点 ID 和节点密钥会在领域服务中再次进行精确校验。
 */
@RestController
@RequestMapping("/api/v1/node/skill-bundles")
public class NodeSkillBundleController {

    static final String NODE_ID_HEADER = "X-Agent-Studio-Node-Id";
    static final String RELEASE_DIGEST_HEADER = "X-Agent-Studio-Release-Digest";
    static final String BUNDLE_DIGEST_HEADER = "X-Agent-Studio-Bundle-Digest";

    private final NodeService nodes;
    private final SkillCatalog skills;

    public NodeSkillBundleController(NodeService nodes, SkillCatalog skills) {
        this.nodes = nodes;
        this.skills = skills;
    }

    @GetMapping(path = "/{skillId}/{releaseHex}", produces = "application/zip")
    public ResponseEntity<Resource> download(
            @PathVariable String skillId,
            @PathVariable String releaseHex,
            @RequestHeader(value = NODE_ID_HEADER, required = false) String nodeId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        if (releaseHex == null || !releaseHex.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("Skill release digest must be 64 hexadecimal characters.");
        }
        nodes.authenticateNode(nodeId, bearerSecret(authorization));
        SkillBundleDownload bundle = skills.prepareBundle(skillId, "sha256:" + releaseHex.toLowerCase());
        String filename = bundle.skillId() + "-" + releaseHex.toLowerCase() + ".zip";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .contentLength(bundle.sizeBytes())
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .eTag("\"" + bundle.bundleDigest() + "\"")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .header(RELEASE_DIGEST_HEADER, bundle.releaseDigest())
                .header(BUNDLE_DIGEST_HEADER, bundle.bundleDigest())
                .body(new FileSystemResource(bundle.path()));
    }

    private static String bearerSecret(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String secret = authorization.substring(7).trim();
        return secret.isBlank() ? null : secret;
    }
}
