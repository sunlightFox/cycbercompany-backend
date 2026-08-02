package io.github.yourname.agentstudio.node;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.FetchType;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

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
    @Column(length = 71)
    private String capabilityRevision;
    @ElementCollection(fetch = FetchType.EAGER)
    private Map<String, String> runtimeVersions = new LinkedHashMap<>();
    @ElementCollection(fetch = FetchType.EAGER)
    private Set<String> features = new LinkedHashSet<>();
    /**
     * 管理员配置的调度标签，例如 linux、java-21、playwright。标签不是节点自行上报的
     * 能力，也不参与权限判定；它只是让控制面从已被明确标记为 SANDBOX 的节点中选目标。
     */
    @ElementCollection(fetch = FetchType.EAGER)
    private Set<String> labels = new LinkedHashSet<>();
    @Column(length = 128)
    private String secretHash;
    @Enumerated(EnumType.STRING)
    private NodeKind kind;
    private boolean enabled;
    @Enumerated(EnumType.STRING)
    private NodeStatus status;
    private Instant lastSeenAt;
    private Instant createdAt;
    private Instant updatedAt;
    private long fencingToken;

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
        this(id, tenantId, name, hostname, osName, osArch, clientVersion, secretHash, NodeKind.REGISTERED, now);
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
            NodeKind kind,
            Instant now) {
        this.id = id;
        this.tenantId = tenantId;
        this.name = name;
        this.hostname = hostname;
        this.osName = osName;
        this.osArch = osArch;
        this.clientVersion = clientVersion;
        this.secretHash = secretHash;
        this.kind = kind == null ? NodeKind.REGISTERED : kind;
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
    public String capabilityRevision() { return capabilityRevision; }
    public Map<String, String> runtimeVersions() { return Map.copyOf(runtimeVersions); }
    public Set<String> features() { return Set.copyOf(features); }
    public Set<String> labels() { return Set.copyOf(labels); }
    public String secretHash() { return secretHash; }
    public NodeKind kind() { return kind == null ? NodeKind.REGISTERED : kind; }
    public boolean enabled() { return enabled; }
    public NodeStatus status() { return status; }
    public Instant lastSeenAt() { return lastSeenAt; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public long fencingToken() { return fencingToken; }

    /** 每次认证成功建立新连接都递增，旧连接的迟到消息因 token 不匹配而失效。 */
    public long advanceFencingToken(Instant now) {
        this.fencingToken++;
        this.updatedAt = now;
        return this.fencingToken;
    }

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

    /** Updates only server-managed sandbox metadata after NodeService has applied its safety checks. */
    public void updateSchedulingMetadata(NodeKind requestedKind, Set<String> requestedLabels, Instant now) {
        if (requestedKind != null) {
            this.kind = requestedKind;
        }
        if (requestedLabels != null) {
            this.labels.clear();
            this.labels.addAll(requestedLabels);
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

    public void updateCapabilitySnapshot(
            String revision,
            Map<String, String> runtimes,
            Set<String> reportedFeatures,
            Instant now) {
        this.capabilityRevision = revision == null || revision.isBlank() ? this.capabilityRevision : revision.trim();
        this.runtimeVersions.clear();
        if (runtimes != null) {
            runtimes.forEach((name, version) -> {
                if (name != null && !name.isBlank() && version != null && !version.isBlank()) {
                    this.runtimeVersions.put(name.trim(), version.trim());
                }
            });
        }
        this.features.clear();
        if (reportedFeatures != null) {
            reportedFeatures.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .forEach(this.features::add);
        }
        this.updatedAt = now;
    }

    /** 替换凭据摘要并让当前连接状态失效，明文密钥永远不会进入实体。 */
    public void rotateSecret(String newSecretHash, Instant now) {
        if (newSecretHash == null || newSecretHash.isBlank()) {
            throw new IllegalArgumentException("Node secret hash is required.");
        }
        this.secretHash = newSecretHash;
        this.status = enabled ? NodeStatus.OFFLINE : NodeStatus.DISABLED;
        this.updatedAt = now;
    }

    private static String blankToExisting(String value, String existing) {
        return value == null || value.isBlank() ? existing : value.trim();
    }
}
