package io.github.yourname.cycbercompany.tool;

import io.github.yourname.cycbercompany.security.ActorContext;
import io.github.yourname.cycbercompany.skill.SkillRunBinding;
import java.util.ArrayList;
import java.util.List;

/** Provider 发现工具时可见的、已经过控制面验证的运行范围。 */
public record ToolDiscoveryRequest(
        String runId,
        String nodeId,
        List<String> knowledgeBaseIds,
        List<String> mcpConnectionIds,
        List<SkillRunBinding> skillBindings,
        ActorContext actor) {

    public ToolDiscoveryRequest {
        knowledgeBaseIds = copyNonNull(knowledgeBaseIds);
        mcpConnectionIds = copyNonNull(mcpConnectionIds);
        skillBindings = copyNonNull(skillBindings);
        if (actor == null) {
            throw new IllegalArgumentException("Tool discovery requires a trusted actor.");
        }
    }

    /** 兼容 P1 迁移期间只传 MCP 范围的调用方。 */
    public ToolDiscoveryRequest(
            String runId,
            String nodeId,
            List<String> knowledgeBaseIds,
            List<String> mcpConnectionIds,
            ActorContext actor) {
        this(runId, nodeId, knowledgeBaseIds, mcpConnectionIds, List.of(), actor);
    }

    public ToolDiscoveryRequest(
            String runId,
            String nodeId,
            List<String> mcpConnectionIds,
            ActorContext actor) {
        this(runId, nodeId, List.of(), mcpConnectionIds, List.of(), actor);
    }

    private static <T> List<T> copyNonNull(List<T> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<T> sanitized = new ArrayList<>();
        for (T value : values) {
            if (value != null) {
                sanitized.add(value);
            }
        }
        return sanitized.isEmpty() ? List.of() : List.copyOf(sanitized);
    }
}
