package io.github.yourname.cycbercompany.node;

import java.time.Instant;

/**
 * 节点密钥轮换结果。明文密钥只在这一次响应中返回，服务端数据库仍只保存 SHA-256 摘要。
 */
public record RotateNodeSecretResult(
        String nodeId,
        String nodeSecret,
        String websocketUrl,
        Instant rotatedAt) {
}
