package io.github.yourname.agentstudio.nodeclient.runtime;

import io.github.yourname.agentstudio.nodeclient.protocol.NodeCapability;
import io.github.yourname.agentstudio.nodeclient.tools.BrowserTool;
import io.github.yourname.agentstudio.nodeclient.tools.FileTool;
import io.github.yourname.agentstudio.nodeclient.tools.GitTool;
import io.github.yourname.agentstudio.nodeclient.tools.ManagedProcessTool;
import io.github.yourname.agentstudio.nodeclient.tools.ShellTool;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 节点本地工具注册表。
 *
 * <p>当前只上报能力，不执行工具。下一阶段会在这里挂载真正的 ToolHandler。
 */
public class ToolRegistry {

    // capabilities() 声明“可用什么”，execute() 决定“实际上能执行什么”，两者必须同步维护。

    private final BrowserTool browserTool;
    private final FileTool fileTool;
    private final ShellTool shellTool;
    private final GitTool gitTool;
    private final ManagedProcessTool managedProcessTool;

    public ToolRegistry(HttpClient httpClient, Path workspaceRoot) {
        this.browserTool = new BrowserTool(httpClient);
        this.fileTool = workspaceRoot == null ? null : new FileTool(workspaceRoot);
        this.shellTool = workspaceRoot == null ? null : new ShellTool(workspaceRoot);
        this.gitTool = workspaceRoot == null ? null : new GitTool(workspaceRoot);
        this.managedProcessTool = workspaceRoot == null ? null : new ManagedProcessTool(workspaceRoot);
    }

    public List<NodeCapability> capabilities() {
        // HIGH 风险 shell.run 默认禁用且要求审批；上报能力不等于绕过服务端授权。
        List<NodeCapability> capabilities = new ArrayList<>(List.of(
                new NodeCapability(
                        "git.status", "Show concise Git branch and worktree status.", "LOW", gitTool != null, false,
                        objectSchema(Map.of())),
                new NodeCapability(
                        "git.diff", "Show the current Git diff, optionally for one workspace-relative path.", "LOW", gitTool != null, false,
                        objectSchema(Map.of("path", Map.of("type", "string")))),
                new NodeCapability(
                        "fs.list",
                        "List files under an allowed workspace path.",
                        "LOW",
                        fileTool != null,
                        false,
                        objectSchema(Map.of(
                                "path", Map.of("type", "string"),
                                "startLine", Map.of("type", "integer"),
                                "endLine", Map.of("type", "integer")), "path")),
                new NodeCapability(
                        "fs.read",
                        "Read a text file under an allowed workspace path.",
                        "LOW",
                        fileTool != null,
                        false,
                        objectSchema(Map.of(
                                "path", Map.of("type", "string")), "path")),
                new NodeCapability(
                        "fs.search",
                        "Search UTF-8 source files under an allowed workspace path and return matching line numbers.",
                        "LOW",
                        fileTool != null,
                        false,
                        objectSchema(Map.of(
                                "path", Map.of("type", "string"),
                                "query", Map.of("type", "string"),
                                "caseSensitive", Map.of("type", "boolean"),
                                "maxResults", Map.of("type", "integer")), "query")),
                new NodeCapability(
                        "fs.write",
                        "Write a UTF-8 text file under the configured workspace.",
                        "MEDIUM",
                        false,
                        true,
                        objectSchema(Map.of(
                                "path", Map.of("type", "string"),
                                "content", Map.of("type", "string")), "path", "content")),
                new NodeCapability(
                        "fs.apply_patch",
                        "Apply one unambiguous text replacement under the configured workspace.",
                        "MEDIUM",
                        false,
                        true,
                        objectSchema(Map.of(
                                "path", Map.of("type", "string"),
                                "expected", Map.of("type", "string"),
                                "replacement", Map.of("type", "string")), "path", "expected", "replacement")),
                new NodeCapability(
                        "shell.run",
                        "Run a shell command in the configured workspace.",
                        "HIGH",
                        false,
                        true,
                        objectSchema(Map.of(
                                "command", Map.of("type", "string"),
                                "cwd", Map.of("type", "string"),
                                "timeoutSeconds", Map.of("type", "integer")), "command")),
                new NodeCapability(
                        "process.start",
                        "Start a workspace development process under a managed handle. Use a foreground command; do not use Start-Process or nohup.",
                        "HIGH",
                        false,
                        true,
                        objectSchema(Map.of(
                                "command", Map.of("type", "string"),
                                "cwd", Map.of("type", "string"),
                                "stdoutPath", Map.of("type", "string"),
                                "stderrPath", Map.of("type", "string")), "command")),
                new NodeCapability(
                        "process.status",
                        "Inspect a node-managed development process by processId.",
                        "LOW",
                        managedProcessTool != null,
                        false,
                        objectSchema(Map.of("processId", Map.of("type", "string")), "processId")),
                new NodeCapability(
                        "process.stop",
                        "Stop a node-managed development process and its descendants by processId.",
                        "HIGH",
                        false,
                        true,
                        objectSchema(Map.of("processId", Map.of("type", "string")), "processId")),
                new NodeCapability(
                        "browser.open",
                        "Open a URL with Playwright on this node.",
                        "MEDIUM",
                        true,
                        false,
                        objectSchema(Map.of(
                                "url", Map.of("type", "string"),
                                "headless", Map.of("type", "boolean"),
                                "channel", Map.of("type", "string")))),
                new NodeCapability(
                        "browser.snapshot",
                        "Return current Playwright page URL, text preview and visible interactive elements with selectors.",
                        "LOW",
                        true,
                        false,
                        objectSchema(Map.of())),
                new NodeCapability(
                        "browser.wait",
                        "Wait for a visible element on the current Playwright page by selector.",
                        "LOW",
                        true,
                        false,
                        objectSchema(Map.of(
                                "selector", Map.of("type", "string"),
                                "timeoutMs", Map.of("type", "integer")), "selector")),
                new NodeCapability(
                        "browser.screenshot",
                        "Take a PNG screenshot of the current Playwright page.",
                        "LOW",
                        true,
                        false,
                        objectSchema(Map.of(
                                "fullPage", Map.of("type", "boolean"),
                                "path", Map.of("type", "string")))),
                new NodeCapability(
                        "browser.click",
                        "Click an element on the current Playwright page by selector.",
                        "MEDIUM",
                        true,
                        false,
                        objectSchema(Map.of(
                                "selector", Map.of("type", "string")))),
                new NodeCapability(
                        "browser.type",
                        "Fill an input on the current Playwright page by selector.",
                        "MEDIUM",
                        true,
                        false,
                        objectSchema(Map.of(
                                "selector", Map.of("type", "string"),
                                "text", Map.of("type", "string"))))));
        return capabilities;
    }

    private Map<String, Object> objectSchema(Map<String, Object> properties, String... required) {
        Map<String, Object> schema = new java.util.LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (required != null && required.length > 0) {
            schema.put("required", List.of(required));
        }
        return schema;
    }

    public ToolExecutionResult execute(String toolName, Map<String, Object> arguments) {
        return execute(toolName, arguments, null);
    }

    /** executionSessionId is transport metadata used only by stateful local tools. */
    public ToolExecutionResult execute(String toolName, Map<String, Object> arguments, String executionSessionId) {
        if ("browser.close_session".equals(toolName)) {
            return ToolExecutionResult.success(Map.of("closed", browserTool.closeSession(executionSessionId)));
        }
        if ("fs.list".equals(toolName)) {
            return fileTool == null
                    ? ToolExecutionResult.failure("fs.list is unavailable because this node has no configured workspace.")
                    : fileTool.list(arguments);
        }
        if ("fs.read".equals(toolName)) {
            return fileTool == null
                    ? ToolExecutionResult.failure("fs.read is unavailable because this node has no configured workspace.")
                    : fileTool.read(arguments);
        }
        if ("fs.search".equals(toolName)) {
            return fileTool == null
                    ? ToolExecutionResult.failure("fs.search is unavailable because this node has no configured workspace.")
                    : fileTool.search(arguments);
        }
        if ("fs.write".equals(toolName)) {
            return fileTool == null
                    ? ToolExecutionResult.failure("fs.write is unavailable because this node has no configured workspace.")
                    : fileTool.write(arguments);
        }
        if ("fs.apply_patch".equals(toolName)) {
            return fileTool == null
                    ? ToolExecutionResult.failure("fs.apply_patch is unavailable because this node has no configured workspace.")
                    : fileTool.applyPatch(arguments);
        }
        // 显式分派而不是反射调用，使允许执行的本机操作可审计、可枚举。
        if ("shell.run".equals(toolName)) {
            return shellTool == null
                    ? ToolExecutionResult.failure("shell.run is unavailable because this node has no configured workspace.")
                    : shellTool.run(arguments);
        }
        if ("process.start".equals(toolName)) {
            return managedProcessTool == null
                    ? ToolExecutionResult.failure("process.start is unavailable because this node has no configured workspace.")
                    : managedProcessTool.start(arguments);
        }
        if ("process.status".equals(toolName)) {
            return managedProcessTool == null
                    ? ToolExecutionResult.failure("process.status is unavailable because this node has no configured workspace.")
                    : managedProcessTool.status(arguments);
        }
        if ("process.stop".equals(toolName)) {
            return managedProcessTool == null
                    ? ToolExecutionResult.failure("process.stop is unavailable because this node has no configured workspace.")
                    : managedProcessTool.stop(arguments);
        }
        if ("git.status".equals(toolName)) {
            return gitTool == null ? ToolExecutionResult.failure("git.status is unavailable because this node has no configured workspace.") : gitTool.status();
        }
        if ("git.diff".equals(toolName)) {
            return gitTool == null ? ToolExecutionResult.failure("git.diff is unavailable because this node has no configured workspace.") : gitTool.diff(arguments);
        }
        if ("browser.open".equals(toolName)) {
            return browserTool.open(executionSessionId, arguments);
        }
        if ("browser.snapshot".equals(toolName)) {
            return browserTool.snapshot(executionSessionId, arguments);
        }
        if ("browser.wait".equals(toolName)) {
            return browserTool.waitFor(executionSessionId, arguments);
        }
        if ("browser.screenshot".equals(toolName)) {
            return browserTool.screenshot(executionSessionId, arguments);
        }
        if ("browser.click".equals(toolName)) {
            return browserTool.click(executionSessionId, arguments);
        }
        if ("browser.type".equals(toolName)) {
            return browserTool.type(executionSessionId, arguments);
        }
        return ToolExecutionResult.failure("Unsupported node tool: " + toolName);
    }

    public void close() {
        browserTool.close();
        if (managedProcessTool != null) {
            managedProcessTool.close();
        }
    }
}
