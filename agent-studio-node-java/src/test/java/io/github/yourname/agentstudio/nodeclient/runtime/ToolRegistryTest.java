package io.github.yourname.agentstudio.nodeclient.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.yourname.agentstudio.nodeclient.NodeAccessMode;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolRegistryTest {

    @Test
    void advertisesBrowserWaitingWithItsRequiredSelector() throws Exception {
        ToolRegistry registry = new ToolRegistry(HttpClient.newHttpClient(), Files.createTempDirectory("agent-studio-tools"));

        var capability = registry.capabilities().stream()
                .filter(item -> "browser.wait".equals(item.name()))
                .findFirst()
                .orElseThrow();

        assertEquals(java.util.List.of("selector"), capability.inputSchema().get("required"));
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) capability.inputSchema().get("properties");
        assertTrue(properties.containsKey("timeoutMs"));
        registry.close();
    }

    @Test
    void advertisesAccurateWorkspaceFileSchemas() throws Exception {
        ToolRegistry registry = new ToolRegistry(HttpClient.newHttpClient(), Files.createTempDirectory("agent-studio-tools"));

        var search = registry.capabilities().stream()
                .filter(item -> "fs.search".equals(item.name()))
                .findFirst()
                .orElseThrow();
        var list = registry.capabilities().stream()
                .filter(item -> "fs.list".equals(item.name()))
                .findFirst()
                .orElseThrow();
        var read = registry.capabilities().stream()
                .filter(item -> "fs.read".equals(item.name()))
                .findFirst()
                .orElseThrow();
        var batchPatch = registry.capabilities().stream()
                .filter(item -> "fs.apply_patch_batch".equals(item.name()))
                .findFirst()
                .orElseThrow();

        assertEquals(java.util.List.of("query"), search.inputSchema().get("required"));
        assertTrue((Boolean) search.inputSchema().get("additionalProperties") == false);
        Map<String, Object> listProperties = properties(list.inputSchema());
        Map<String, Object> readProperties = properties(read.inputSchema());
        assertTrue(!listProperties.containsKey("startLine") && !listProperties.containsKey("endLine"));
        assertTrue(readProperties.containsKey("startLine") && readProperties.containsKey("endLine"));
        @SuppressWarnings("unchecked")
        Map<String, Object> startLine = (Map<String, Object>) readProperties.get("startLine");
        assertEquals(1, startLine.get("minimum"));
        assertTrue(startLine.get("description").toString().contains("1-based"));
        assertEquals(java.util.List.of("changes"), batchPatch.inputSchema().get("required"));
        assertTrue(batchPatch.description().contains("multiple patches may target one file"));
        registry.close();
    }

    @Test
    void advertisesRequiredBrowserMutationArguments() throws Exception {
        ToolRegistry registry = new ToolRegistry(HttpClient.newHttpClient(), Files.createTempDirectory("agent-studio-tools"));

        var click = registry.capabilities().stream()
                .filter(item -> "browser.click".equals(item.name()))
                .findFirst()
                .orElseThrow();
        var type = registry.capabilities().stream()
                .filter(item -> "browser.type".equals(item.name()))
                .findFirst()
                .orElseThrow();
        var closeTab = registry.capabilities().stream()
                .filter(item -> "browser.close_tab".equals(item.name()))
                .findFirst()
                .orElseThrow();

        assertEquals(java.util.List.of(), click.inputSchema().getOrDefault("required", java.util.List.of()));
        assertEquals(java.util.List.of("text"), type.inputSchema().get("required"));
        assertEquals(java.util.List.of("index"), closeTab.inputSchema().get("required"));
        assertTrue(closeTab.description().contains("only remaining tab"));
        assertTrue(click.description().contains("snapshot again"));
        assertTrue(properties(type.inputSchema()).values().stream()
                .allMatch(property -> ((Map<?, ?>) property).containsKey("description")));
        registry.close();
    }

    @Test
    void advertisesReadOnlyProjectInspection() throws Exception {
        ToolRegistry registry = new ToolRegistry(HttpClient.newHttpClient(), Files.createTempDirectory("agent-studio-tools"));

        var capability = registry.capabilities().stream()
                .filter(item -> "project.inspect".equals(item.name()))
                .findFirst()
                .orElseThrow();

        assertEquals(java.util.List.of(), capability.inputSchema().getOrDefault("required", java.util.List.of()));
        registry.close();
    }

    @Test
    void keepsGitWritingToolsDisabledAndApprovalProtected() throws Exception {
        ToolRegistry registry = new ToolRegistry(HttpClient.newHttpClient(), Files.createTempDirectory("agent-studio-tools"));

        var stage = registry.capabilities().stream().filter(item -> "git.stage".equals(item.name())).findFirst().orElseThrow();
        var commit = registry.capabilities().stream().filter(item -> "git.commit".equals(item.name())).findFirst().orElseThrow();

        // 注册能力不代表自动放权：管理员还必须显式启用，运行时还必须逐次审批。
        assertEquals(java.util.List.of("paths"), stage.inputSchema().get("required"));
        assertEquals(java.util.List.of("message"), commit.inputSchema().get("required"));
        registry.close();
    }

    @Test
    void acceptsTheBackendOnlyBrowserSessionCleanupCommand() throws Exception {
        ToolRegistry registry = new ToolRegistry(HttpClient.newHttpClient(), Files.createTempDirectory("agent-studio-tools"));

        var result = registry.execute("browser.close_session", Map.of(), "run-1");

        assertTrue(result.success());
        assertEquals(false, result.result().get("closed"));
        registry.close();
    }

    @Test
    void internalBrowserSessionCleanupRejectsBlankRunIds() throws Exception {
        ToolRegistry registry = new ToolRegistry(HttpClient.newHttpClient(), Files.createTempDirectory("agent-studio-tools"));

        // 空运行标识在 BrowserTool 中兼容地代表 default 会话；取消清理入口必须拒绝它，
        // 防止一个缺少 Run 元数据的协议帧误关掉其他调用的页面。
        assertFalse(registry.closeExecutionSession(null));
        assertFalse(registry.closeExecutionSession("   "));
        registry.close();
    }

    @Test
    void systemModeAdvertisesApprovalProtectedComputerTools() throws Exception {
        var desktop = Files.createTempDirectory("agent-studio-desktop-tools");
        Files.writeString(desktop.resolve("inbox.txt"), "sort me");
        ToolRegistry registry = new ToolRegistry(
                HttpClient.newHttpClient(),
                Files.createTempDirectory("agent-studio-system-tools"),
                NodeAccessMode.SYSTEM,
                desktop);

        var mkdir = registry.capabilities().stream().filter(item -> "system.fs.mkdir".equals(item.name())).findFirst().orElseThrow();
        var move = registry.capabilities().stream().filter(item -> "system.fs.move".equals(item.name())).findFirst().orElseThrow();
        var shell = registry.capabilities().stream().filter(item -> "system.shell.run".equals(item.name())).findFirst().orElseThrow();
        var softwareQuery = registry.capabilities().stream()
                .filter(item -> "system.software.query".equals(item.name()))
                .findFirst()
                .orElseThrow();
        var softwareList = registry.capabilities().stream()
                .filter(item -> "system.software.list".equals(item.name()))
                .findFirst()
                .orElseThrow();
        var softwareInstall = registry.capabilities().stream()
                .filter(item -> "system.software.install".equals(item.name()))
                .findFirst()
                .orElseThrow();
        var softwareUninstall = registry.capabilities().stream()
                .filter(item -> "system.software.uninstall".equals(item.name()))
                .findFirst()
                .orElseThrow();
        var serviceQuery = registry.capabilities().stream()
                .filter(item -> "system.service.query".equals(item.name()))
                .findFirst()
                .orElseThrow();
        var serviceStop = registry.capabilities().stream()
                .filter(item -> "system.service.stop".equals(item.name()))
                .findFirst()
                .orElseThrow();
        var serviceSetStartMode = registry.capabilities().stream()
                .filter(item -> "system.service.set_start_mode".equals(item.name()))
                .findFirst()
                .orElseThrow();
        var osProcessQuery = registry.capabilities().stream()
                .filter(item -> "system.os_process.query".equals(item.name()))
                .findFirst()
                .orElseThrow();
        var osProcessTerminate = registry.capabilities().stream()
                .filter(item -> "system.os_process.terminate".equals(item.name()))
                .findFirst()
                .orElseThrow();
        var privilegeQuery = registry.capabilities().stream()
                .filter(item -> "system.privilege.query".equals(item.name()))
                .findFirst()
                .orElseThrow();
        var uninstallPreflight = registry.capabilities().stream()
                .filter(item -> "system.uninstall.preflight".equals(item.name()))
                .findFirst()
                .orElseThrow();
        var uninstallExecute = registry.capabilities().stream()
                .filter(item -> "system.uninstall.execute".equals(item.name()))
                .findFirst()
                .orElseThrow();
        var systemProcessStart = registry.capabilities().stream()
                .filter(item -> "system.process.start".equals(item.name()))
                .findFirst()
                .orElseThrow();
        var scopedList = registry.capabilities().stream()
                .filter(item -> "system.desktop.organize.list".equals(item.name()))
                .findFirst()
                .orElseThrow();
        var scopedMove = registry.capabilities().stream()
                .filter(item -> "system.desktop.organize.move".equals(item.name()))
                .findFirst()
                .orElseThrow();
        var scopedWrite = registry.capabilities().stream()
                .filter(item -> "system.desktop.organize.write".equals(item.name()))
                .findFirst()
                .orElseThrow();
        var scopedDelete = registry.capabilities().stream()
                .filter(item -> "system.desktop.organize.delete".equals(item.name()))
                .findFirst()
                .orElseThrow();

        assertEquals(java.util.List.of("source", "destination"), move.inputSchema().get("required"));
        assertEquals(java.util.List.of("command"), shell.inputSchema().get("required"));
        assertEquals(java.util.List.of("packageId"), softwareQuery.inputSchema().get("required"));
        assertEquals(java.util.List.of(), softwareList.inputSchema().getOrDefault("required", java.util.List.of()));
        assertTrue(properties(softwareList.inputSchema()).containsKey("timeoutSeconds"));
        assertTrue(softwareList.description().contains("winget"));
        assertEquals(java.util.List.of("packageId"), softwareInstall.inputSchema().get("required"));
        assertEquals(java.util.List.of("packageId"), softwareUninstall.inputSchema().get("required"));
        assertTrue(properties(softwareInstall.inputSchema()).containsKey("allowUpgrade"));
        assertTrue(softwareInstall.description().contains("--no-upgrade"));
        assertTrue(softwareUninstall.description().contains("protected services"));
        assertTrue(registry.features().contains("system-software.v1"));
        assertEquals(java.util.List.of("serviceName"), serviceQuery.inputSchema().get("required"));
        assertEquals(java.util.List.of("serviceName"), serviceStop.inputSchema().get("required"));
        assertEquals(java.util.List.of("serviceName", "startMode"), serviceSetStartMode.inputSchema().get("required"));
        assertTrue(serviceStop.description().contains("not-stoppable services"));
        assertTrue(properties(serviceSetStartMode.inputSchema()).containsKey("startMode"));
        assertTrue(registry.features().contains("system-service.v1"));
        assertEquals(java.util.List.of("processName"), osProcessQuery.inputSchema().get("required"));
        assertEquals(java.util.List.of("processName"), osProcessTerminate.inputSchema().get("required"));
        assertTrue(properties(osProcessTerminate.inputSchema()).containsKey("allMatching"));
        assertTrue(osProcessQuery.description().contains("separate from system.process"));
        assertTrue(registry.features().contains("system-os-process.v1"));
        assertEquals(java.util.List.of(), privilegeQuery.inputSchema().getOrDefault("required", java.util.List.of()));
        assertTrue(privilegeQuery.description().contains("administrator token"));
        assertTrue(registry.features().contains("system-privilege.v1"));
        assertEquals(java.util.List.of("packageId"), uninstallPreflight.inputSchema().get("required"));
        assertTrue(properties(uninstallPreflight.inputSchema()).containsKey("serviceName"));
        assertTrue(properties(uninstallPreflight.inputSchema()).containsKey("processNames"));
        assertTrue(uninstallPreflight.description().contains("blocking signals"));
        assertTrue(registry.features().contains("system-uninstall-preflight.v1"));
        assertEquals(java.util.List.of("packageId"), uninstallExecute.inputSchema().get("required"));
        assertTrue(properties(uninstallExecute.inputSchema()).containsKey("stopService"));
        assertTrue(properties(uninstallExecute.inputSchema()).containsKey("terminateProcesses"));
        assertTrue(properties(uninstallExecute.inputSchema()).containsKey("retryUninstall"));
        assertTrue(uninstallExecute.description().contains("retry one exact winget uninstall"));
        assertTrue(registry.features().contains("system-uninstall-execute.v1"));
        assertTrue(shell.description().contains(
                io.github.yourname.agentstudio.nodeclient.tools.ShellTool.commandDialectDescription()));
        assertTrue(systemProcessStart.description().contains("long-running process"));
        @SuppressWarnings("unchecked")
        var shellProperties = (java.util.Map<String, Object>) shell.inputSchema().get("properties");
        @SuppressWarnings("unchecked")
        var commandSchema = (java.util.Map<String, Object>) shellProperties.get("command");
        assertTrue(commandSchema.get("description").toString().contains(
                io.github.yourname.agentstudio.nodeclient.tools.ShellTool.commandDialectDescription()));
        @SuppressWarnings("unchecked")
        var mkdirProperties = (java.util.Map<String, Object>) mkdir.inputSchema().get("properties");
        @SuppressWarnings("unchecked")
        var mkdirPathSchema = (java.util.Map<String, Object>) mkdirProperties.get("path");
        assertTrue(mkdirPathSchema.get("description").toString().contains("Concrete absolute directory path"));
        assertEquals(java.util.List.of(), scopedList.inputSchema().getOrDefault("required", java.util.List.of()));
        assertEquals(java.util.List.of("filename", "content"), scopedWrite.inputSchema().get("required"));
        assertEquals(java.util.List.of("source", "category"), scopedMove.inputSchema().get("required"));
        assertEquals(java.util.List.of("source"), scopedDelete.inputSchema().get("required"));
        assertTrue(registry.execute("system.desktop.organize.write", Map.of(
                "filename", "\u9759\u591c\u601d.txt", "content", "\u5e8a\u524d\u660e\u6708\u5149")).success());
        assertEquals("\u5e8a\u524d\u660e\u6708\u5149", Files.readString(desktop.resolve("\u9759\u591c\u601d.txt")));
        registry.close();
    }

    @Test
    void advertisesReadOnlyGitReviewBeforeCodingDelivery() throws Exception {
        ToolRegistry registry = new ToolRegistry(HttpClient.newHttpClient(), Files.createTempDirectory("agent-studio-git-review-tools"));

        var review = registry.capabilities().stream()
                .filter(item -> "git.review".equals(item.name()))
                .findFirst()
                .orElseThrow();

        // 审阅只返回变更摘要，不接收路径或内容参数，因此模型不能借此读取工作区外的数据。
        assertEquals(java.util.List.of(), review.inputSchema().getOrDefault("required", java.util.List.of()));
        assertTrue(properties(review.inputSchema()).isEmpty());
        assertTrue(review.description().contains("Does not read file contents"));
        registry.close();
    }

    @Test
    void advertisesAnExplicitStagedGitDiffOption() throws Exception {
        ToolRegistry registry = new ToolRegistry(HttpClient.newHttpClient(), Files.createTempDirectory("agent-studio-git-diff-tools"));

        var diff = registry.capabilities().stream()
                .filter(item -> "git.diff".equals(item.name()))
                .findFirst()
                .orElseThrow();

        Map<String, Object> properties = properties(diff.inputSchema());
        assertTrue(properties.containsKey("staged"));
        assertTrue(diff.description().contains("staged=true"));
        registry.close();
    }

    @Test
    void advertisesBoundedProjectSymbolNavigation() throws Exception {
        ToolRegistry registry = new ToolRegistry(HttpClient.newHttpClient(), Files.createTempDirectory("agent-studio-symbol-tools"));

        var capability = registry.capabilities().stream()
                .filter(item -> "project.symbols".equals(item.name()))
                .findFirst()
                .orElseThrow();

        Map<String, Object> properties = properties(capability.inputSchema());
        assertTrue(properties.containsKey("query"));
        assertTrue(properties.containsKey("includeTests"));
        assertTrue(properties.containsKey("maxResults"));
        registry.close();
    }

    @Test
    void advertisesBoundedCandidateReferenceNavigation() throws Exception {
        ToolRegistry registry = new ToolRegistry(HttpClient.newHttpClient(), Files.createTempDirectory("agent-studio-reference-tools"));

        var capability = registry.capabilities().stream()
                .filter(item -> "project.references".equals(item.name()))
                .findFirst()
                .orElseThrow();

        assertEquals(java.util.List.of("symbol"), capability.inputSchema().get("required"));
        Map<String, Object> properties = properties(capability.inputSchema());
        assertTrue(properties.containsKey("symbol"));
        assertTrue(properties.containsKey("includeTests"));
        assertTrue(properties.containsKey("maxResults"));
        registry.close();
    }

    @Test
    void dispatchesCandidateReferenceSearchToTheConfiguredWorkspace() throws Exception {
        var workspace = Files.createTempDirectory("agent-studio-reference-dispatch");
        Files.writeString(workspace.resolve("TaskService.java"), "class TaskService {}\nclass Consumer { TaskService service; }\n");
        ToolRegistry registry = new ToolRegistry(HttpClient.newHttpClient(), workspace);

        var result = registry.execute("project.references", Map.of("symbol", "TaskService"), "run-reference-search");

        assertTrue(result.success());
        @SuppressWarnings("unchecked")
        var references = (java.util.List<Map<String, Object>>) result.result().get("references");
        assertEquals(2, references.size());
        registry.close();
    }

    @Test
    void advertisesBoundedReadOnlyProjectDiagnostics() throws Exception {
        ToolRegistry registry = new ToolRegistry(HttpClient.newHttpClient(), Files.createTempDirectory("agent-studio-diagnostic-tools"));

        var capability = registry.capabilities().stream()
                .filter(item -> "project.diagnose".equals(item.name()))
                .findFirst()
                .orElseThrow();

        // 节点能力协议不携带风险和审批字段，二者只能由服务端策略目录决定。
        assertEquals("1", capability.version());
        assertEquals(java.util.List.of("output"), capability.inputSchema().get("required"));
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) properties(capability.inputSchema()).get("output");
        assertEquals(49152, output.get("maxLength"));
        registry.close();
    }

    @Test
    void advertisesBoundedBrowserAndDesktopInteractionSchemas() throws Exception {
        ToolRegistry registry = new ToolRegistry(
                HttpClient.newHttpClient(), Files.createTempDirectory("agent-studio-interaction-tools"), NodeAccessMode.SYSTEM);

        var press = registry.capabilities().stream().filter(item -> "browser.press".equals(item.name())).findFirst().orElseThrow();
        var select = registry.capabilities().stream().filter(item -> "browser.select_option".equals(item.name())).findFirst().orElseThrow();
        var uiClick = registry.capabilities().stream().filter(item -> "system.desktop.ui.click".equals(item.name())).findFirst().orElseThrow();
        var uiVerify = registry.capabilities().stream().filter(item -> "system.desktop.ui.verify".equals(item.name())).findFirst().orElseThrow();
        var uiWait = registry.capabilities().stream().filter(item -> "system.desktop.ui.wait".equals(item.name())).findFirst().orElseThrow();
        var uiReadValue = registry.capabilities().stream().filter(item -> "system.desktop.ui.read_value".equals(item.name())).findFirst().orElseThrow();
        var desktopScreenshot = registry.capabilities().stream().filter(item -> "system.desktop.screenshot".equals(item.name())).findFirst().orElseThrow();
        var clipboardSet = registry.capabilities().stream().filter(item -> "system.desktop.clipboard.set".equals(item.name())).findFirst().orElseThrow();

        assertEquals(java.util.List.of("key"), press.inputSchema().get("required"));
        assertTrue(properties(select.inputSchema()).containsKey("label"));
        assertEquals(java.util.List.of("ref", "snapshotRevision"), uiClick.inputSchema().get("required"));
        assertTrue(properties(uiClick.inputSchema()).containsKey("ref"));
        assertTrue(properties(uiClick.inputSchema()).containsKey("snapshotRevision"));
        assertEquals(java.util.List.of("processId"), uiVerify.inputSchema().get("required"));
        assertEquals(java.util.List.of("processId"), uiWait.inputSchema().get("required"));
        assertTrue(properties(uiWait.inputSchema()).containsKey("timeoutMs"));
        assertTrue(uiWait.description().contains("30 seconds"));
        assertEquals(java.util.List.of("processId"), uiReadValue.inputSchema().get("required"));
        assertTrue(uiReadValue.description().contains("Refuses password controls"));
        assertFalse(desktopScreenshot.inputSchema().containsKey("required"));
        assertTrue(desktopScreenshot.description().contains("Artifact"));
        assertEquals(java.util.List.of("text"), clipboardSet.inputSchema().get("required"));
        registry.close();
    }

    @Test
    void advertisesReadOnlyManagedProcessLogs() throws Exception {
        ToolRegistry registry = new ToolRegistry(HttpClient.newHttpClient(), Files.createTempDirectory("agent-studio-process-log-tools"));

        var capability = registry.capabilities().stream()
                .filter(item -> "process.logs".equals(item.name()))
                .findFirst()
                .orElseThrow();

        assertEquals(java.util.List.of("processId"), capability.inputSchema().get("required"));
        Map<String, Object> properties = properties(capability.inputSchema());
        assertTrue(properties.containsKey("stream"));
        assertTrue(properties.containsKey("maxChars"));
        registry.close();
    }

    @Test
    void advertisesReadOnlyLoopbackHttpReadinessForManagedProcesses() throws Exception {
        ToolRegistry registry = new ToolRegistry(HttpClient.newHttpClient(), Files.createTempDirectory("agent-studio-process-http-tools"));

        var capability = registry.capabilities().stream()
                .filter(item -> "process.wait_http".equals(item.name()))
                .findFirst()
                .orElseThrow();

        assertEquals(java.util.List.of("processId", "url"), capability.inputSchema().get("required"));
        Map<String, Object> properties = properties(capability.inputSchema());
        assertTrue(properties.containsKey("timeoutMs"));
        assertTrue(properties.containsKey("expectedStatus"));
        assertTrue(capability.description().contains("remote addresses are not allowed"));
        registry.close();
    }

    @Test
    void advertisesReadOnlyBrowserVerification() throws Exception {
        ToolRegistry registry = new ToolRegistry(HttpClient.newHttpClient(), Files.createTempDirectory("agent-studio-browser-verify-tools"));

        var capability = registry.capabilities().stream()
                .filter(item -> "browser.verify".equals(item.name()))
                .findFirst()
                .orElseThrow();

        assertEquals(java.util.List.of("checks"), capability.inputSchema().get("required"));
        assertTrue(properties(capability.inputSchema()).containsKey("checks"));
        assertTrue(capability.description().contains("response URL/status"));
        registry.close();
    }

    @Test
    void advertisesBoundedPostActionBrowserResponseWaiting() throws Exception {
        ToolRegistry registry = new ToolRegistry(HttpClient.newHttpClient(), Files.createTempDirectory("agent-studio-browser-response-tools"));

        var capability = registry.capabilities().stream()
                .filter(item -> "browser.wait_response".equals(item.name()))
                .findFirst()
                .orElseThrow();

        Map<String, Object> properties = properties(capability.inputSchema());
        assertTrue(properties.containsKey("status"));
        assertTrue(properties.containsKey("urlContains"));
        assertTrue(properties.containsKey("timeoutMs"));
        assertTrue(capability.description().contains("after the latest browser page action"));
        registry.close();
    }

    @Test
    void workspaceModeDoesNotAdvertiseSystemTools() throws Exception {
        ToolRegistry registry = new ToolRegistry(
                HttpClient.newHttpClient(), Files.createTempDirectory("agent-studio-workspace-tools"), NodeAccessMode.WORKSPACE);

        assertTrue(registry.capabilities().stream().noneMatch(item -> item.name().startsWith("system.")));
        registry.close();
    }

    @Test
    void workspaceModeRejectsDirectSystemToolExecution() throws Exception {
        ToolRegistry registry = new ToolRegistry(
                HttpClient.newHttpClient(), Files.createTempDirectory("agent-studio-workspace-execute"), NodeAccessMode.WORKSPACE);

        var list = registry.execute("system.fs.list", Map.of("path", "."));
        var shell = registry.execute("system.shell.run", Map.of("command", "echo should-not-run"));
        var process = registry.execute("system.process.start", Map.of("command", "npm run dev"));

        assertFalse(list.success());
        assertFalse(shell.success());
        assertFalse(process.success());
        assertTrue(list.errorMessage().contains("not registered with system access"));
        assertTrue(shell.errorMessage().contains("not registered with system access"));
        assertTrue(process.errorMessage().contains("not registered with system access"));
        registry.close();
    }

    @Test
    void doesNotAdvertiseManagedProcessToolsWithoutAWorkspace() throws Exception {
        ToolRegistry registry = new ToolRegistry(HttpClient.newHttpClient(), null, NodeAccessMode.WORKSPACE);

        assertTrue(registry.capabilities().stream().noneMatch(item -> item.name().startsWith("process.")));
        assertTrue(registry.capabilities().stream().noneMatch(item -> item.name().startsWith("system.process.")));
        registry.close();
    }

    @Test
    void reportsRuntimeAndFeatureFactsSeparatelyFromToolPermissions() throws Exception {
        ToolRegistry registry = new ToolRegistry(
                HttpClient.newHttpClient(), Files.createTempDirectory("agent-studio-runtime-facts"), NodeAccessMode.WORKSPACE);

        assertTrue(registry.runtimeVersions().containsKey("java"));
        assertFalse(registry.runtimeVersions().containsKey("privilege.mode"));
        assertTrue(registry.features().contains("workspace.scope.v1"));
        assertTrue(registry.features().contains("managed-process.v1"));
        assertTrue(registry.features().stream().noneMatch("system-access.v1"::equals));
        assertTrue(registry.capabilities().stream().allMatch(capability -> capability.version() != null));
        registry.close();
    }

    @Test
    void systemModeReportsPrivilegeRuntimeFact() throws Exception {
        ToolRegistry registry = new ToolRegistry(
                HttpClient.newHttpClient(), Files.createTempDirectory("agent-studio-runtime-privilege"), NodeAccessMode.SYSTEM);

        assertTrue(registry.runtimeVersions().containsKey("privilege.mode"));
        registry.close();
    }

    @Test
    void systemModeExecutesAbsolutePathFileToolsThroughTheRegistry() throws Exception {
        var workspace = Files.createTempDirectory("agent-studio-system-workspace");
        var outside = Files.createTempDirectory("agent-studio-system-outside").resolve("system-note.txt");
        ToolRegistry registry = new ToolRegistry(HttpClient.newHttpClient(), workspace, NodeAccessMode.SYSTEM);

        // 这是节点真实分发表的验证：模型调用 system.fs.write 后，绝对路径不能被改写为工作区子路径。
        var write = registry.execute("system.fs.write", Map.of("path", outside.toString(), "content", "system access verified"));
        var read = registry.execute("system.fs.read", Map.of("path", outside.toString()));

        assertTrue(write.success());
        assertTrue(read.success());
        assertEquals(outside.toRealPath().toString(), read.result().get("path"));
        assertEquals("system access verified", read.result().get("content"));
        registry.close();
    }

    @Test
    void systemModeCanStartAndStopManagedProcessesOutsideTheWorkspace() throws Exception {
        var workspace = Files.createTempDirectory("agent-studio-system-process-workspace");
        var outside = Files.createTempDirectory("agent-studio-system-process-outside");
        ToolRegistry registry = new ToolRegistry(HttpClient.newHttpClient(), workspace, NodeAccessMode.SYSTEM);

        var started = registry.execute("system.process.start", Map.of(
                "command", longRunningCommand(),
                "cwd", outside.toString()));

        assertTrue(started.success());
        assertEquals("system", started.result().get("workingDirectoryScope"));
        assertFalse(started.result().containsKey("workingDirectory"));
        var stopped = registry.execute("system.process.stop", Map.of("processId", started.result().get("processId")));
        assertTrue(stopped.success());
        registry.close();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(Map<String, Object> schema) {
        return (Map<String, Object>) schema.get("properties");
    }

    private static String longRunningCommand() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? "ping -n 20 127.0.0.1 > nul"
                : "sleep 20";
    }
}
