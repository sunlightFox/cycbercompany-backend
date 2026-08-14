package io.github.yourname.cycbercompany.tool;

import io.github.yourname.cycbercompany.security.ActorContext;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 已绑定 Provider 的一次工具调用。
 *
 * <p>只有 {@code arguments} 来自模型，其余字段由服务端从 RunSpec、ActorContext 和策略中填充。
 * 这条规则防止模型通过参数偷偷切换到另一个节点或租户。
 */
public record ToolInvocationRequest(
        String runId,
        String toolCallId,
        ResolvedToolBinding binding,
        Map<String, Object> arguments,
        Integer timeoutSeconds,
        CodingWorkspaceScope workspaceScope,
        ActorContext actor,
        String approvalId,
        ApprovalMode approvalMode,
        AgentApprovalPolicy agentApprovalPolicy) {

    public ToolInvocationRequest {
        if (binding == null) {
            throw new IllegalArgumentException("Tool invocation requires a resolved binding.");
        }
        arguments = sanitizeArguments(arguments);
        workspaceScope = workspaceScope == null ? CodingWorkspaceScope.from(null) : workspaceScope;
        if (actor == null) {
            throw new IllegalArgumentException("Tool invocation requires a trusted actor.");
        }
        approvalMode = approvalMode == null ? ApprovalMode.ON_REQUEST : approvalMode;
        agentApprovalPolicy = agentApprovalPolicy == null ? AgentApprovalPolicy.sessionOnly() : agentApprovalPolicy;
    }

    public ToolInvocationRequest(
            String runId,
            String toolCallId,
            ResolvedToolBinding binding,
            Map<String, Object> arguments,
            Integer timeoutSeconds,
            CodingWorkspaceScope workspaceScope,
            ActorContext actor) {
        this(runId, toolCallId, binding, arguments, timeoutSeconds, workspaceScope, actor, null,
                ApprovalMode.ON_REQUEST, AgentApprovalPolicy.sessionOnly());
    }

    public ToolInvocationRequest(
            String runId,
            String toolCallId,
            ResolvedToolBinding binding,
            Map<String, Object> arguments,
            Integer timeoutSeconds,
            CodingWorkspaceScope workspaceScope,
            ActorContext actor,
            String approvalId) {
        this(runId, toolCallId, binding, arguments, timeoutSeconds, workspaceScope, actor, approvalId,
                ApprovalMode.ON_REQUEST, AgentApprovalPolicy.sessionOnly());
    }

    public ToolInvocationRequest(
            String runId,
            String toolCallId,
            ResolvedToolBinding binding,
            Map<String, Object> arguments,
            Integer timeoutSeconds,
            CodingWorkspaceScope workspaceScope,
            ActorContext actor,
            String approvalId,
            ApprovalMode approvalMode) {
        this(runId, toolCallId, binding, arguments, timeoutSeconds, workspaceScope, actor, approvalId,
                approvalMode, AgentApprovalPolicy.sessionOnly());
    }

    private static Map<String, Object> sanitizeArguments(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        arguments.forEach((key, value) -> {
            if (key != null && value != null) {
                sanitized.put(key, value);
            }
        });
        return sanitized.isEmpty() ? Map.of() : Map.copyOf(sanitized);
    }
}
