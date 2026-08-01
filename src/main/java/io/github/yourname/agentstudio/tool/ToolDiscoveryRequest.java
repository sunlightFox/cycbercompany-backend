package io.github.yourname.agentstudio.tool;

import io.github.yourname.agentstudio.security.ActorContext;
import java.util.List;

/** Provider 发现工具时可见的、已经过控制面验证的运行范围。 */
public record ToolDiscoveryRequest(
        String runId,
        String nodeId,
        List<String> knowledgeBaseIds,
        List<String> mcpConnectionIds,
        ActorContext actor) {

    public ToolDiscoveryRequest {
        knowledgeBaseIds = knowledgeBaseIds == null ? List.of() : List.copyOf(knowledgeBaseIds);
        mcpConnectionIds = mcpConnectionIds == null ? List.of() : List.copyOf(mcpConnectionIds);
        if (actor == null) {
            throw new IllegalArgumentException("Tool discovery requires a trusted actor.");
        }
    }

    /** 兼容 P1 迁移期间只传 MCP 范围的调用方。 */
    public ToolDiscoveryRequest(
            String runId,
            String nodeId,
            List<String> mcpConnectionIds,
            ActorContext actor) {
        this(runId, nodeId, List.of(), mcpConnectionIds, actor);
    }
}
