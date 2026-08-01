package io.github.yourname.agentstudio.tool;

import io.github.yourname.agentstudio.security.ActorContext;
import java.util.Map;

/** 已绑定 Provider 的一次工具调用；只有 arguments 来自模型，其余字段均由服务端填充。 */
public record ToolInvocationRequest(
        String runId,
        String toolCallId,
        ResolvedToolBinding binding,
        Map<String, Object> arguments,
        Integer timeoutSeconds,
        CodingWorkspaceScope workspaceScope,
        ActorContext actor,
        String approvalId) {

    public ToolInvocationRequest {
        if (binding == null) {
            throw new IllegalArgumentException("Tool invocation requires a resolved binding.");
        }
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        workspaceScope = workspaceScope == null ? CodingWorkspaceScope.from(null) : workspaceScope;
        if (actor == null) {
            throw new IllegalArgumentException("Tool invocation requires a trusted actor.");
        }
    }

    public ToolInvocationRequest(
            String runId,
            String toolCallId,
            ResolvedToolBinding binding,
            Map<String, Object> arguments,
            Integer timeoutSeconds,
            CodingWorkspaceScope workspaceScope,
            ActorContext actor) {
        this(runId, toolCallId, binding, arguments, timeoutSeconds, workspaceScope, actor, null);
    }
}
