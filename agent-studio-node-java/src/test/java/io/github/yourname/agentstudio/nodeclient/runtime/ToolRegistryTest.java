package io.github.yourname.agentstudio.nodeclient.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void advertisesWorkspaceSearchWithARequiredQuery() throws Exception {
        ToolRegistry registry = new ToolRegistry(HttpClient.newHttpClient(), Files.createTempDirectory("agent-studio-tools"));

        var capability = registry.capabilities().stream()
                .filter(item -> "fs.search".equals(item.name()))
                .findFirst()
                .orElseThrow();

        assertEquals(java.util.List.of("query"), capability.inputSchema().get("required"));
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
    void systemModeAdvertisesApprovalProtectedComputerTools() throws Exception {
        ToolRegistry registry = new ToolRegistry(
                HttpClient.newHttpClient(), Files.createTempDirectory("agent-studio-system-tools"), NodeAccessMode.SYSTEM);

        var move = registry.capabilities().stream().filter(item -> "system.fs.move".equals(item.name())).findFirst().orElseThrow();
        var shell = registry.capabilities().stream().filter(item -> "system.shell.run".equals(item.name())).findFirst().orElseThrow();

        assertEquals(java.util.List.of("source", "destination"), move.inputSchema().get("required"));
        assertEquals(java.util.List.of("command"), shell.inputSchema().get("required"));
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
    void reportsRuntimeAndFeatureFactsSeparatelyFromToolPermissions() throws Exception {
        ToolRegistry registry = new ToolRegistry(
                HttpClient.newHttpClient(), Files.createTempDirectory("agent-studio-runtime-facts"), NodeAccessMode.WORKSPACE);

        assertTrue(registry.runtimeVersions().containsKey("java"));
        assertTrue(registry.features().contains("workspace.scope.v1"));
        assertTrue(registry.features().contains("managed-process.v1"));
        assertTrue(registry.features().stream().noneMatch("system-access.v1"::equals));
        assertTrue(registry.capabilities().stream().allMatch(capability -> capability.version() != null));
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
}
