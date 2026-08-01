package io.github.yourname.agentstudio.node;

import io.github.yourname.agentstudio.tool.RiskLevel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;

/**
 * 节点上报的具体工具。
 *
 * <p>工具启用粒度必须落到“某个节点里的某个工具”，例如 office-pc 的 shell.run 可以关闭，
 * 但 server-01 的 shell.run 可以打开。
 */
@Entity(name = "node_tool")
public class NodeToolEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String tenantId;
    private String nodeId;
    private String name;
    private String capabilityVersion;
    @Column(length = 2_000)
    private String description;
    @Enumerated(EnumType.STRING)
    private RiskLevel riskLevel;
    private boolean enabled;
    private boolean requiresApproval;
    @Column(length = 20_000)
    private String inputSchemaJson;
    private Instant createdAt;
    private Instant updatedAt;

    protected NodeToolEntity() {
    }

    public NodeToolEntity(
            String tenantId,
            String nodeId,
            String name,
            String description,
            RiskLevel riskLevel,
            boolean enabled,
            boolean requiresApproval,
            String inputSchemaJson,
            Instant now) {
        this.tenantId = tenantId;
        this.nodeId = nodeId;
        this.name = name;
        this.capabilityVersion = "1";
        this.description = description;
        this.riskLevel = riskLevel;
        this.enabled = enabled;
        this.requiresApproval = requiresApproval;
        this.inputSchemaJson = inputSchemaJson;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public Long id() { return id; }
    public String tenantId() { return tenantId; }
    public String nodeId() { return nodeId; }
    public String name() { return name; }
    public String capabilityVersion() { return capabilityVersion == null ? "1" : capabilityVersion; }
    public String description() { return description; }
    public RiskLevel riskLevel() { return riskLevel; }
    public boolean enabled() { return enabled; }
    public boolean requiresApproval() { return requiresApproval; }
    public String inputSchemaJson() { return inputSchemaJson; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }

    public void refresh(String description, RiskLevel riskLevel, boolean requiresApproval, String inputSchemaJson, Instant now) {
        this.description = description;
        this.riskLevel = riskLevel == null ? RiskLevel.MEDIUM : riskLevel;
        this.requiresApproval = requiresApproval;
        this.inputSchemaJson = inputSchemaJson;
        this.updatedAt = now;
    }

    /**
     * Refreshes node-reported metadata while preserving the administrator's runtime policy.
     */
    public void refreshCapability(String description, RiskLevel riskLevel, String inputSchemaJson, Instant now) {
        refreshCapability(description, riskLevel, inputSchemaJson, "1", now);
    }

    public void refreshCapability(
            String description,
            RiskLevel riskLevel,
            String inputSchemaJson,
            String capabilityVersion,
            Instant now) {
        this.description = description;
        this.riskLevel = riskLevel == null ? RiskLevel.MEDIUM : riskLevel;
        this.inputSchemaJson = inputSchemaJson;
        this.capabilityVersion = capabilityVersion == null || capabilityVersion.isBlank()
                ? "1"
                : capabilityVersion.trim();
        this.updatedAt = now;
    }

    public void updatePolicy(Boolean enabled, Boolean requiresApproval, Instant now) {
        if (enabled != null) {
            this.enabled = enabled;
        }
        if (requiresApproval != null) {
            this.requiresApproval = requiresApproval;
        }
        this.updatedAt = now;
    }
}
