package io.github.yourname.agentstudio.node;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.time.Instant;

/**
 * 节点注册令牌。
 *
 * <p>令牌是短期凭证，只用于第一次注册节点。注册成功后会立即标记为 used，
 * 后续节点连接使用 nodeId + nodeSecret。
 */
@Entity(name = "node_registration_token")
public class NodeRegistrationTokenEntity {

    @Id
    private String id;
    private String tenantId;
    @Column(length = 128)
    private String tokenHash;
    private Instant expiresAt;
    private boolean used;
    private Instant createdAt;
    private Instant usedAt;

    protected NodeRegistrationTokenEntity() {
    }

    public NodeRegistrationTokenEntity(String id, String tenantId, String tokenHash, Instant expiresAt, Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }

    public String id() { return id; }
    public String tenantId() { return tenantId; }
    public String tokenHash() { return tokenHash; }
    public Instant expiresAt() { return expiresAt; }
    public boolean used() { return used; }
    public Instant createdAt() { return createdAt; }
    public Instant usedAt() { return usedAt; }

    public boolean expired(Instant now) {
        return expiresAt != null && expiresAt.isBefore(now);
    }

    public void markUsed(Instant now) {
        this.used = true;
        this.usedAt = now;
    }
}
