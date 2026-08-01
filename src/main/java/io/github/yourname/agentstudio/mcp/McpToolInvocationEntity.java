package io.github.yourname.agentstudio.mcp;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import java.time.Instant;

/**
 * MCP 工具调用审计。
 *
 * <p>参数只保存键名和 SHA-256 摘要，结果只保存内容条目数；正文、凭据和原始 MCP 返回值
 * 不进入审计表，降低数据库备份泄露外部系统数据的风险。
 */
@Entity(name = "mcp_tool_invocation")
public class McpToolInvocationEntity {

    @Id
    private String id;
    private String tenantId;
    private String actorUserId;
    private String runId;
    private String connectionId;
    private String toolName;
    @Enumerated(EnumType.STRING)
    private McpToolInvocationStatus status;
    private String argumentKeys;
    private String argumentsSha256;
    private Integer resultContentItems;
    private String errorCategory;
    private Instant createdAt;
    private Instant startedAt;
    private Instant finishedAt;

    protected McpToolInvocationEntity() {
    }

    public McpToolInvocationEntity(
            String id,
            String tenantId,
            String actorUserId,
            String runId,
            String connectionId,
            String toolName,
            String argumentKeys,
            String argumentsSha256,
            Instant now) {
        this.id = id;
        this.tenantId = tenantId;
        this.actorUserId = actorUserId;
        this.runId = runId;
        this.connectionId = connectionId;
        this.toolName = toolName;
        this.argumentKeys = argumentKeys;
        this.argumentsSha256 = argumentsSha256;
        this.status = McpToolInvocationStatus.REQUESTED;
        this.createdAt = now;
    }

    public void start(Instant now) {
        status = McpToolInvocationStatus.RUNNING;
        startedAt = now;
    }

    public void succeed(int contentItems, Instant now) {
        status = McpToolInvocationStatus.SUCCEEDED;
        resultContentItems = Math.max(contentItems, 0);
        finishedAt = now;
    }

    public void fail(String category, Instant now) {
        status = McpToolInvocationStatus.FAILED;
        errorCategory = category;
        finishedAt = now;
    }

    public void deny(String category, Instant now) {
        status = McpToolInvocationStatus.DENIED;
        errorCategory = category;
        finishedAt = now;
    }

    public String id() { return id; }
    public String tenantId() { return tenantId; }
    public String actorUserId() { return actorUserId; }
    public String runId() { return runId; }
    public String connectionId() { return connectionId; }
    public String toolName() { return toolName; }
    public McpToolInvocationStatus status() { return status; }
    public String argumentKeys() { return argumentKeys; }
    public String argumentsSha256() { return argumentsSha256; }
    public Integer resultContentItems() { return resultContentItems; }
    public String errorCategory() { return errorCategory; }
    public Instant createdAt() { return createdAt; }
    public Instant startedAt() { return startedAt; }
    public Instant finishedAt() { return finishedAt; }
}
