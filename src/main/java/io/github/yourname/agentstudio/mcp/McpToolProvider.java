package io.github.yourname.agentstudio.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yourname.agentstudio.tool.ToolDescriptor;
import io.github.yourname.agentstudio.tool.ToolDiscoveryRequest;
import io.github.yourname.agentstudio.tool.ToolInvocationRequest;
import io.github.yourname.agentstudio.tool.ToolProvider;
import io.github.yourname.agentstudio.tool.ToolProviderResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 将用户在 Run 中明确选择的 MCP 连接转换为不可变工具绑定。
 *
 * <p>模型看到的是随机化后的 modelName，执行时则使用 binding 中固定的 connectionId 和原始
 * MCP tool name。这样即使参数中出现同名字段，也不能把调用偷偷转发到另一个 MCP 服务。
 */
@Service
public class McpToolProvider implements ToolProvider {

    public static final String PROVIDER_ID = "mcp";

    private final McpConnectionService connections;
    private final ObjectMapper objectMapper;

    public McpToolProvider(McpConnectionService connections, ObjectMapper objectMapper) {
        this.connections = connections;
        this.objectMapper = objectMapper;
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public List<ToolDescriptor> discover(ToolDiscoveryRequest request) {
        return request.mcpConnectionIds().stream()
                .distinct()
                .flatMap(connectionId -> descriptors(connectionId).stream())
                .toList();
    }

    private List<ToolDescriptor> descriptors(String connectionId) {
        McpConnectionView connection = connections.getConnection(connectionId);
        if (!connection.enabled()) {
            throw new IllegalArgumentException("Selected MCP connection is disabled: " + connectionId);
        }
        return connection.tools().stream()
                .filter(McpToolView::enabled)
                .map(tool -> new ToolDescriptor(
                        bindingId(connectionId, tool.name()),
                        tool.name(),
                        PROVIDER_ID,
                        tool.name(),
                        "MCP " + connection.name() + " / " + tool.name() + ": " + blank(tool.description()),
                        tool.riskLevel(),
                        tool.requiresApproval(),
                        readSchema(tool.inputSchema()),
                        Map.of("connectionId", connectionId)))
                .toList();
    }

    @Override
    public ToolProviderResult invoke(ToolInvocationRequest request) {
        if (!PROVIDER_ID.equals(request.binding().providerId())) {
            throw new IllegalArgumentException("McpToolProvider cannot invoke binding: " + request.binding().bindingId());
        }
        String connectionId = request.binding().attributes().get("connectionId");
        if (connectionId == null || connectionId.isBlank()) {
            throw new IllegalArgumentException("MCP binding has no fixed connectionId: " + request.binding().bindingId());
        }
        try {
            McpToolCallResult result = request.approvalId() == null || request.approvalId().isBlank()
                    ? connections.callTool(
                            connectionId,
                            request.binding().providerToolName(),
                            new CallMcpToolCommand(request.arguments()),
                            request.runId(),
                            request.actor())
                    : connections.callToolAfterApproval(
                            connectionId,
                            request.binding().providerToolName(),
                            new CallMcpToolCommand(request.arguments()),
                            request.runId(),
                            request.actor());
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("connectionId", connectionId);
            content.put("tool", request.binding().providerToolName());
            content.put("text", result.text() == null ? "" : result.text());
            content.put("content", result.content() == null ? List.of() : result.content());
            return new ToolProviderResult(
                    result.error() ? "FAILED" : "SUCCEEDED",
                    !result.error(),
                    content,
                    result.error() ? "MCP server returned an error result." : "",
                    null);
        } catch (Exception ex) {
            // requiresApproval=true 的 MCP 工具目前由 McpConnectionService 明确拒绝，绝不降级直调。
            return new ToolProviderResult(
                    "FAILED",
                    false,
                    Map.of("connectionId", connectionId, "tool", request.binding().providerToolName()),
                    message(ex),
                    null);
        }
    }

    static String bindingId(String connectionId, String toolName) {
        return "mcp:" + connectionId + ":" + toolName;
    }

    private Map<String, Object> readSchema(String schemaJson) {
        if (schemaJson == null || schemaJson.isBlank()) {
            return emptySchema();
        }
        try {
            return objectMapper.readValue(schemaJson, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (Exception ignored) {
            return emptySchema();
        }
    }

    private static Map<String, Object> emptySchema() {
        return Map.of("type", "object", "properties", Map.of());
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? "No description provided." : value;
    }

    private static String message(Exception ex) {
        return ex.getMessage() == null || ex.getMessage().isBlank()
                ? ex.getClass().getSimpleName()
                : ex.getMessage();
    }
}
