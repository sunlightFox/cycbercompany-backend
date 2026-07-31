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
        return "project.inspect".equals(name)
                || "project.discover".equals(name)
                || "project.map".equals(name)
                || "system.fs.list".equals(name)
                || "system.fs.read".equals(name)
                || "system.fs.search".equals(name)
                || "system.fs.write".equals(name)
                || "system.fs.apply_patch".equals(name)
                || "system.fs.mkdir".equals(name)
                || "system.fs.move".equals(name)
                || "system.fs.delete".equals(name)
                || "system.shell.run".equals(name)
                || "fs.list".equals(name)
                || "fs.read".equals(name)
                || "fs.search".equals(name)
                || "fs.write".equals(name)
                || "fs.apply_patch".equals(name)
                || "shell.run".equals(name)
                || "process.start".equals(name)
                || "process.status".equals(name)
                || "process.stop".equals(name)
                || "git.status".equals(name)
                || "git.diff".equals(name)
                || "git.stage".equals(name)
                || "git.commit".equals(name)
                || "browser.open".equals(name)
                || "browser.snapshot".equals(name)
                || "browser.wait".equals(name)
                || "browser.screenshot".equals(name)
                || "browser.click".equals(name)
                || "browser.type".equals(name)
                || "system.fs.list".equals(name)
                || "system.fs.read".equals(name)
                || "system.fs.search".equals(name)
                || "system.fs.write".equals(name)
                || "system.fs.apply_patch".equals(name)
                || "system.fs.mkdir".equals(name)
                || "system.fs.move".equals(name)
                || "system.fs.delete".equals(name)
                || "system.shell.run".equals(name);
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
            Map<String, Object> resultContent = new LinkedHashMap<>();
            resultContent.put("status", result.status());
            resultContent.put("tool", tool.nodeToolName());
            resultContent.put("result", result.result() == null ? Map.of() : result.result());
            resultContent.put("error", result.errorMessage() == null ? "" : result.errorMessage());
            // 命令输出通常很长。把常见失败模式提炼成结构化字段，让模型先定位文件或测试，
            // 仍保留原始结果，方便遇到未知工具链时继续阅读真实日志。
            Map<String, Object> diagnosis = CodingFailureSummary.from(
                    tool.nodeToolName(), "SUCCEEDED".equalsIgnoreCase(result.status()), result.result(), result.errorMessage());
            if (!diagnosis.isEmpty()) {
                resultContent.put("diagnosis", diagnosis);
            }
            String content = serialize(resultContent);
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
        return java.util.stream.Stream.concat(
                        nodes.cleanupManagedProcessesForRun(runId, actor).stream(),
                        nodes.cleanupBrowserSessionsForRun(runId, actor).stream())
                .map(result -> new CleanupResult(
                        result.nodeId(),
                        result.toolName(),
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
        if ("system.fs.list".equals(toolName)
                || "system.fs.read".equals(toolName)
                || "system.fs.search".equals(toolName)
                || "system.fs.write".equals(toolName)
                || "system.fs.apply_patch".equals(toolName)
                || "system.fs.mkdir".equals(toolName)
                || "system.fs.move".equals(toolName)
                || "system.fs.delete".equals(toolName)
                || "system.shell.run".equals(toolName)) {
            // system.* 工具本身已经声明“绝对路径”，不能再套用项目工作区范围。
            return scoped;
        }
        if ("fs.list".equals(toolName)
                || "fs.read".equals(toolName)
                || "fs.search".equals(toolName)
                || "fs.write".equals(toolName)
                || "fs.apply_patch".equals(toolName)) {
            scopeArgument(scoped, "path", workspaceScope);
        }
        if ("project.inspect".equals(toolName) || "project.discover".equals(toolName) || "project.map".equals(toolName)) {
            scopeArgument(scoped, "cwd", workspaceScope);
        }
        if (("git.diff".equals(toolName) || "browser.screenshot".equals(toolName)) && scoped.containsKey("path")) {
            scopeArgument(scoped, "path", workspaceScope);
        }
        if ("git.stage".equals(toolName)) {
            scopePathList(scoped, "paths", workspaceScope);
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

    /** 每个待暂存文件都必须单独落在本次编码任务选择的项目范围内。 */
    private static void scopePathList(Map<String, Object> arguments, String name, CodingWorkspaceScope workspaceScope) {
        Object value = arguments.get(name);
        if (!(value instanceof List<?> values)) {
            throw new IllegalArgumentException("Node tool argument '" + name + "' must be an array of strings.");
        }
        List<String> scoped = new java.util.ArrayList<>();
        for (Object item : values) {
            if (!(item instanceof String path)) {
                throw new IllegalArgumentException("Node tool argument '" + name + "' must be an array of strings.");
            }
            scoped.add(workspaceScope.resolve(path));
        }
        arguments.put(name, scoped);
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

    public record CleanupResult(String nodeId, String toolName, boolean succeeded, String errorMessage) {
    }
}
