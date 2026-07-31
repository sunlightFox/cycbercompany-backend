package io.github.yourname.agentstudio.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yourname.agentstudio.model.ModelGateway;
import io.github.yourname.agentstudio.node.CallNodeToolCommand;
import io.github.yourname.agentstudio.node.NodeService;
import io.github.yourname.agentstudio.node.NodeToolCallResult;
import io.github.yourname.agentstudio.node.NodeToolView;
import io.github.yourname.agentstudio.security.ActorContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Presents one node's administrator-approved capabilities as model function calls.
 *
 * <p>The model never receives a node secret or a WebSocket/session reference. This
 * adapter is the narrow boundary between orchestration and node execution.
 */
@Service
public class CodingToolAdapter {

    private static final int MAX_TOOL_RESULT_CHARS = 12_000;

    private final NodeService nodes;
    private final ObjectMapper objectMapper;

    public CodingToolAdapter(NodeService nodes, ObjectMapper objectMapper) {
        this.nodes = nodes;
        this.objectMapper = objectMapper;
    }

    public List<AvailableTool> availableTools(String nodeId, ActorContext actor) {
        if (!nodes.isReadyForToolExecution(nodeId, actor)) {
            throw new IllegalArgumentException("The selected node is not connected for tool execution: " + nodeId);
        }
        return nodes.listTools(nodeId, actor).stream()
                .filter(NodeToolView::enabled)
                .filter(tool -> isCodingTool(tool.name()))
                .map(this::availableTool)
                .toList();
    }

    private static boolean isCodingTool(String name) {
        return "fs.list".equals(name)
                || "fs.read".equals(name)
                || "fs.write".equals(name)
                || "fs.apply_patch".equals(name)
                || "shell.run".equals(name)
                || "process.start".equals(name)
                || "process.status".equals(name)
                || "process.stop".equals(name)
                || "git.status".equals(name)
                || "git.diff".equals(name)
                || "browser.open".equals(name)
                || "browser.snapshot".equals(name)
                || "browser.wait".equals(name)
                || "browser.screenshot".equals(name)
                || "browser.click".equals(name)
                || "browser.type".equals(name);
    }

    public ToolExecution execute(
            String runId,
            AvailableTool tool,
            ModelGateway.ModelToolCall call,
            ActorContext actor) {
        return execute(runId, tool, call, actor, CodingWorkspaceScope.from(null));
    }

    public ToolExecution execute(
            String runId,
            AvailableTool tool,
            ModelGateway.ModelToolCall call,
            ActorContext actor,
            CodingWorkspaceScope workspaceScope) {
        try {
            Map<String, Object> scopedArguments = scopedArguments(
                    tool.nodeToolName(),
                    call.arguments(),
                    workspaceScope == null ? CodingWorkspaceScope.from(null) : workspaceScope);
            NodeToolCallResult result = nodes.callToolForRun(
                    runId,
                    call.id(),
                    tool.nodeId(),
                    tool.nodeToolName(),
                    new CallNodeToolCommand(scopedArguments, timeoutSeconds(scopedArguments)),
                    actor);
            String content = serialize(Map.of(
                    "status", result.status(),
                    "tool", tool.nodeToolName(),
                    "result", result.result() == null ? Map.of() : result.result(),
                    "error", result.errorMessage() == null ? "" : result.errorMessage()));
            return new ToolExecution(
                    "SUCCEEDED".equalsIgnoreCase(result.status()),
                    content,
                    approvalId(result));
        } catch (Exception ex) {
            return new ToolExecution(false, serialize(Map.of(
                    "status", "FAILED",
                    "tool", tool.nodeToolName(),
                    "error", message(ex))));
        }
    }

    /** Stops only managed processes the backend recorded for this run. */
    public List<CleanupResult> cleanupRun(String runId, ActorContext actor) {
        return nodes.cleanupManagedProcessesForRun(runId, actor).stream()
                .map(result -> new CleanupResult(
                        result.nodeId(),
                        "SUCCEEDED".equalsIgnoreCase(result.status()),
                        result.errorMessage()))
                .toList();
    }

    private AvailableTool availableTool(NodeToolView tool) {
        return new AvailableTool(
                "node_tool_" + tool.id(),
                tool.nodeId(),
                tool.name(),
                new ModelGateway.ModelTool(
                        "node_tool_" + tool.id(),
                        "Node tool '" + tool.name() + "': " + blankToDefault(tool.description(), "No description provided.")
                                + (tool.requiresApproval() ? " This call requires human approval before execution." : ""),
                        readSchema(tool.inputSchemaJson())));
    }

    private static String approvalId(NodeToolCallResult result) {
        if (!"APPROVAL_REQUIRED".equalsIgnoreCase(result.status()) || result.result() == null) {
            return null;
        }
        Object value = result.result().get("approvalId");
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    private static Map<String, Object> scopedArguments(
            String toolName,
            Map<String, Object> arguments,
            CodingWorkspaceScope workspaceScope) {
        Map<String, Object> scoped = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
        if ("fs.list".equals(toolName)
                || "fs.read".equals(toolName)
                || "fs.write".equals(toolName)
                || "fs.apply_patch".equals(toolName)) {
            scopeArgument(scoped, "path", workspaceScope);
        }
        if (("git.diff".equals(toolName) || "browser.screenshot".equals(toolName)) && scoped.containsKey("path")) {
            scopeArgument(scoped, "path", workspaceScope);
        }
        if ("shell.run".equals(toolName) || "process.start".equals(toolName)) {
            scopeArgument(scoped, "cwd", workspaceScope);
        }
        return scoped;
    }

    private static void scopeArgument(Map<String, Object> arguments, String name, CodingWorkspaceScope workspaceScope) {
        Object value = arguments.get(name);
        if (value != null && !(value instanceof String)) {
            throw new IllegalArgumentException("Node tool argument '" + name + "' must be a string.");
        }
        arguments.put(name, workspaceScope.resolve((String) value));
    }

    private Map<String, Object> readSchema(String schemaJson) {
        if (schemaJson == null || schemaJson.isBlank()) {
            return Map.of("type", "object", "properties", Map.of());
        }
        try {
            return objectMapper.readValue(schemaJson, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception ex) {
            // A malformed capability report must not cause an unrelated coding
            // run to fail. The backend still retains the original report for audit.
            return Map.of("type", "object", "properties", Map.of());
        }
    }

    private String serialize(Object value) {
        try {
            String json = objectMapper.writeValueAsString(value);
            return json.length() <= MAX_TOOL_RESULT_CHARS
                    ? json
                    : json.substring(0, MAX_TOOL_RESULT_CHARS) + "... [tool result truncated]";
        } catch (Exception ex) {
            return "{\"status\":\"FAILED\",\"error\":\"Unable to serialize tool result\"}";
        }
    }

    private static Integer timeoutSeconds(Map<String, Object> arguments) {
        Object value = arguments == null ? null : arguments.get("timeoutSeconds");
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String message(Exception ex) {
        return ex.getMessage() == null || ex.getMessage().isBlank()
                ? ex.getClass().getSimpleName()
                : ex.getMessage();
    }

    public record AvailableTool(
            String modelToolName,
            String nodeId,
            String nodeToolName,
            ModelGateway.ModelTool modelTool) {
    }

    public record ToolExecution(boolean succeeded, String content, String approvalId) {

        public ToolExecution(boolean succeeded, String content) {
            this(succeeded, content, null);
        }

        public boolean requiresApproval() {
            return approvalId != null && !approvalId.isBlank();
        }
    }

    public record CleanupResult(String nodeId, boolean succeeded, String errorMessage) {
    }
}
