package io.github.yourname.agentstudio.nodeclient.runtime;

import io.github.yourname.agentstudio.nodeclient.NodeAccessMode;
import io.github.yourname.agentstudio.nodeclient.protocol.NodeCapability;
import io.github.yourname.agentstudio.nodeclient.tools.BrowserTool;
import io.github.yourname.agentstudio.nodeclient.tools.DesktopTool;
import io.github.yourname.agentstudio.nodeclient.tools.FileTool;
import io.github.yourname.agentstudio.nodeclient.tools.GitTool;
import io.github.yourname.agentstudio.nodeclient.tools.ManagedProcessTool;
import io.github.yourname.agentstudio.nodeclient.tools.ProjectTool;
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
    private final ProjectTool projectTool;
    private final DesktopTool desktopTool;
    private final boolean systemAccess;

    public ToolRegistry(HttpClient httpClient, Path workspaceRoot) {
        this(httpClient, workspaceRoot, NodeAccessMode.WORKSPACE);
    }

    public ToolRegistry(HttpClient httpClient, Path workspaceRoot, NodeAccessMode accessMode) {
        this.systemAccess = accessMode != null && accessMode.permitsSystemAccess();
        this.browserTool = new BrowserTool(httpClient);
        this.fileTool = workspaceRoot == null ? null : new FileTool(workspaceRoot, systemAccess);
        this.shellTool = workspaceRoot == null ? null : new ShellTool(workspaceRoot, systemAccess);
        this.gitTool = workspaceRoot == null ? null : new GitTool(workspaceRoot);
        this.managedProcessTool = workspaceRoot == null ? null : new ManagedProcessTool(workspaceRoot);
        this.projectTool = workspaceRoot == null ? null : new ProjectTool(workspaceRoot);
        // 是否允许系统级命令由节点注册时的显式模式决定；是否真正执行仍由服务端审批决定。
        this.desktopTool = systemAccess ? new DesktopTool() : null;
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
                        "git.stage",
                        "Stage explicit workspace-relative files for a later Git commit. Requires human approval.",
                        "HIGH",
                        false,
                        true,
                        objectSchema(Map.of("paths", Map.of("type", "array", "items", Map.of("type", "string"))), "paths")),
                new NodeCapability(
                        "git.commit",
                        "Create a Git commit from already staged changes. Requires human approval.",
                        "HIGH",
                        false,
                        true,
                        objectSchema(Map.of("message", Map.of("type", "string")), "message")),
                new NodeCapability(
                        "project.inspect",
                        "Detect the workspace project type and return manifest-backed build, test, and start command recommendations without executing them.",
                        "LOW",
                        projectTool != null,
                        false,
                        objectSchema(Map.of("cwd", Map.of("type", "string")))),
                new NodeCapability(
                        "project.discover",
                        "Discover manifest-backed project roots below a workspace directory without entering dependency or build-output folders.",
                        "LOW",
                        projectTool != null,
                        false,
                        objectSchema(Map.of("cwd", Map.of("type", "string")))),
                new NodeCapability(
                        "project.map",
                        "Map discovered modules to existing source roots, test roots, and configuration files without reading full source files.",
                        "LOW",
                        projectTool != null,
                        false,
                        objectSchema(Map.of("cwd", Map.of("type", "string")))),
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
                                "channel", Map.of("type", "string")), "url")),
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
                                "fullPage", Map.of("type", "boolean")))),
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
                                "text", Map.of("type", "string")))),
                new NodeCapability(
                        "browser.trace.start",
                        "Start recording a Playwright trace for the current browser session.",
                        "LOW",
                        true,
                        false,
                        objectSchema(Map.of())),
                new NodeCapability(
                        "browser.trace.stop",
                        "Stop recording and export a local Playwright trace ZIP for the current browser session.",
                        "LOW",
                        true,
                        false,
                        objectSchema(Map.of()))));
        if (systemAccess) {
            capabilities.addAll(systemCapabilities());
        }
        return capabilities;
    }

    /** Skill 兼容预检只使用可验证的运行时事实，不在这里推断权限。 */
    public Map<String, String> runtimeVersions() {
        Map<String, String> runtimes = new java.util.LinkedHashMap<>();
        runtimes.put("java", System.getProperty("java.version", "unknown"));
        runtimes.put("os", System.getProperty("os.name", "unknown"));
        return Map.copyOf(runtimes);
    }

    /** 协议 feature 表示实现特性，不表示该能力已获管理员授权。 */
    public List<String> features() {
        List<String> result = new ArrayList<>();
        result.add("tool.invoke.v1");
        result.add("workspace.scope.v1");
        result.add("managed-process.v1");
        result.add("browser-session.v1");
        if (systemAccess) {
            result.add("system-access.v1");
        }
        return List.copyOf(result);
    }

    private List<NodeCapability> systemCapabilities() {
        return List.of(
                new NodeCapability("system.fs.list", "List files anywhere on this computer or server. Requires human approval.", "MEDIUM", true, true,
                        objectSchema(Map.of("path", Map.of("type", "string")), "path")),
                new NodeCapability("system.fs.read", "Read a text file anywhere on this computer or server. Requires human approval.", "MEDIUM", true, true,
                        objectSchema(Map.of("path", Map.of("type", "string")), "path")),
                new NodeCapability("system.fs.search", "Search text files anywhere on this computer or server. Requires human approval.", "HIGH", true, true,
                        objectSchema(Map.of("path", Map.of("type", "string"), "query", Map.of("type", "string"), "caseSensitive", Map.of("type", "boolean"), "maxResults", Map.of("type", "integer")), "path", "query")),
                new NodeCapability("system.fs.write", "Write a UTF-8 text file anywhere on this computer or server. Requires human approval.", "HIGH", true, true,
                        objectSchema(Map.of("path", Map.of("type", "string"), "content", Map.of("type", "string")), "path", "content")),
                new NodeCapability("system.fs.apply_patch", "Apply one literal replacement to a text file anywhere on this computer or server. Requires human approval.", "HIGH", true, true,
                        objectSchema(Map.of("path", Map.of("type", "string"), "expected", Map.of("type", "string"), "replacement", Map.of("type", "string")), "path", "expected", "replacement")),
                new NodeCapability("system.fs.mkdir", "Create a directory anywhere on this computer or server. Requires human approval.", "HIGH", true, true,
                        objectSchema(Map.of("path", Map.of("type", "string")), "path")),
                new NodeCapability("system.fs.move", "Move or rename a file or directory anywhere on this computer or server. Requires human approval.", "HIGH", true, true,
                        objectSchema(Map.of("source", Map.of("type", "string"), "destination", Map.of("type", "string"), "replaceExisting", Map.of("type", "boolean")), "source", "destination")),
                new NodeCapability("system.fs.delete", "Delete a file or directory anywhere on this computer or server. Requires human approval.", "HIGH", true, true,
                        objectSchema(Map.of("path", Map.of("type", "string"), "recursive", Map.of("type", "boolean")), "path")),
                new NodeCapability("system.shell.run", "Run a shell command from any directory on this computer or server. Requires human approval.", "HIGH", true, true,
                        objectSchema(Map.of("command", Map.of("type", "string"), "cwd", Map.of("type", "string"), "timeoutSeconds", Map.of("type", "integer")), "command")),
                new NodeCapability("system.desktop.set_wallpaper", "Set the current Windows user's desktop wallpaper to an approved image path.", "HIGH", true, true,
                        objectSchema(Map.of("path", Map.of("type", "string")), "path")));
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
        if ("system.fs.list".equals(toolName)) {
            return fileTool == null ? unavailable(toolName) : fileTool.list(arguments);
        }
        if ("system.fs.read".equals(toolName)) {
            return fileTool == null ? unavailable(toolName) : fileTool.read(arguments);
        }
        if ("system.fs.search".equals(toolName)) {
            return fileTool == null ? unavailable(toolName) : fileTool.search(arguments);
        }
        if ("system.fs.write".equals(toolName)) {
            return fileTool == null ? unavailable(toolName) : fileTool.write(arguments);
        }
        if ("system.fs.apply_patch".equals(toolName)) {
            return fileTool == null ? unavailable(toolName) : fileTool.applyPatch(arguments);
        }
        if ("system.fs.mkdir".equals(toolName)) {
            return fileTool == null ? unavailable(toolName) : fileTool.createDirectory(arguments);
        }
        if ("system.fs.move".equals(toolName)) {
            return fileTool == null ? unavailable(toolName) : fileTool.move(arguments);
        }
        if ("system.fs.delete".equals(toolName)) {
            return fileTool == null ? unavailable(toolName) : fileTool.delete(arguments);
        }
        if ("system.shell.run".equals(toolName)) {
            return shellTool == null ? unavailable(toolName) : shellTool.run(arguments);
        }
        if ("system.desktop.set_wallpaper".equals(toolName)) {
            return desktopTool == null ? unavailable(toolName) : desktopTool.setWallpaper(arguments);
        }
        if ("fs.list".equals(toolName)) {
            return fileTool == null
                    ? ToolExecutionResult.failure("fs.list is unavailable because this node has no configured workspace.")
                    : fileTool.list(arguments);
        }
        if ("project.inspect".equals(toolName)) {
            return projectTool == null
                    ? ToolExecutionResult.failure("project.inspect is unavailable because this node has no configured workspace.")
                    : projectTool.inspect(arguments);
        }
        if ("project.discover".equals(toolName)) {
            return projectTool == null
                    ? ToolExecutionResult.failure("project.discover is unavailable because this node has no configured workspace.")
                    : projectTool.discover(arguments);
        }
        if ("project.map".equals(toolName)) {
            return projectTool == null
                    ? ToolExecutionResult.failure("project.map is unavailable because this node has no configured workspace.")
                    : projectTool.map(arguments);
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
        if ("git.stage".equals(toolName)) {
            return gitTool == null ? ToolExecutionResult.failure("git.stage is unavailable because this node has no configured workspace.") : gitTool.stage(arguments);
        }
        if ("git.commit".equals(toolName)) {
            return gitTool == null ? ToolExecutionResult.failure("git.commit is unavailable because this node has no configured workspace.") : gitTool.commit(arguments);
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
        if ("browser.trace.start".equals(toolName)) {
            return browserTool.startTrace(executionSessionId, arguments);
        }
        if ("browser.trace.stop".equals(toolName)) {
            return browserTool.stopTrace(executionSessionId, arguments);
        }
        return ToolExecutionResult.failure("Unsupported node tool: " + toolName);
    }

    private static ToolExecutionResult unavailable(String toolName) {
        return ToolExecutionResult.failure(toolName + " is unavailable because this node has no configured workspace.");
    }

    public void close() {
        browserTool.close();
        if (managedProcessTool != null) {
            managedProcessTool.close();
        }
    }
}
