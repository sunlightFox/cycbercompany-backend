package io.github.yourname.agentstudio.node;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import java.time.Instant;

/**
 * 执行节点连接信息。
 *
 * <p>一台电脑或服务器安装节点客户端后，会注册成一条 NodeConnection。
 * 后端只保存密钥摘要，不保存节点密钥明文；明文只在注册成功响应里返回一次。
 */
@Entity(name = "node_connection")
public class NodeConnectionEntity {

    @Id
    private String id;
    private String tenantId;
    private String name;
    private String hostname;
    private String osName;
    private String osArch;
    private String clientVersion;
    @Column(length = 128)
    private String secretHash;
    private boolean enabled;
    @Enumerated(EnumType.STRING)
    private NodeStatus status;
    private Instant lastSeenAt;
    private Instant createdAt;
    private Instant updatedAt;

    protected NodeConnectionEntity() {
    }

    public NodeConnectionEntity(
            String id,
            String tenantId,
            String name,
            String hostname,
            String osName,
            String osArch,
            String clientVersion,
            String secretHash,
            Instant now) {
        this.id = id;
        this.tenantId = tenantId;
        this.name = name;
        this.hostname = hostname;
        this.osName = osName;
        this.osArch = osArch;
        this.clientVersion = clientVersion;
        this.secretHash = secretHash;
        this.enabled = true;
        this.status = NodeStatus.OFFLINE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String id() { return id; }
    public String tenantId() { return tenantId; }
    public String name() { return name; }
    public String hostname() { return hostname; }
    public String osName() { return osName; }
    public String osArch() { return osArch; }
    public String clientVersion() { return clientVersion; }
    public String secretHash() { return secretHash; }
    public boolean enabled() { return enabled; }
    public NodeStatus status() { return status; }
    public Instant lastSeenAt() { return lastSeenAt; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }

    public void update(String name, boolean enabled, Instant now) {
        if (name != null && !name.isBlank()) {
            this.name = name.trim();
        }
        this.enabled = enabled;
        if (!enabled) {
            this.status = NodeStatus.DISABLED;
        } else if (this.status == NodeStatus.DISABLED) {
            this.status = NodeStatus.OFFLINE;
        }
        this.updatedAt = now;
    }

    public void markOnline(Instant now) {
        if (!enabled) {
            this.status = NodeStatus.DISABLED;
        } else {
            this.status = NodeStatus.ONLINE;
        }
        this.lastSeenAt = now;
        this.updatedAt = now;
    }

    public void markOffline(Instant now) {
        if (!enabled) {
            this.status = NodeStatus.DISABLED;
        } else {
            this.status = NodeStatus.OFFLINE;
        }
        this.updatedAt = now;
    }

    public void refreshMetadata(String hostname, String osName, String osArch, String clientVersion, Instant now) {
        this.hostname = blankToExisting(hostname, this.hostname);
        this.osName = blankToExisting(osName, this.osName);
        this.osArch = blankToExisting(osArch, this.osArch);
        this.clientVersion = blankToExisting(clientVersion, this.clientVersion);
        this.updatedAt = now;
    }

    private static String blankToExisting(String value, String existing) {
        return value == null || value.isBlank() ? existing : value.trim();
    }
}
