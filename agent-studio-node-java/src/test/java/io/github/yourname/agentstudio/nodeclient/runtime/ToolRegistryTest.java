package io.github.yourname.agentstudio.nodeclient.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        assertTrue(capability.enabled());
        assertEquals("LOW", capability.riskLevel());
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

        assertTrue(capability.enabled());
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

        assertTrue(capability.enabled());
        assertEquals("LOW", capability.riskLevel());
        assertEquals(java.util.List.of(), capability.inputSchema().getOrDefault("required", java.util.List.of()));
        registry.close();
    }

    @Test
    void keepsGitWritingToolsDisabledAndApprovalProtected() throws Exception {
        ToolRegistry registry = new ToolRegistry(HttpClient.newHttpClient(), Files.createTempDirectory("agent-studio-tools"));

        var stage = registry.capabilities().stream().filter(item -> "git.stage".equals(item.name())).findFirst().orElseThrow();
        var commit = registry.capabilities().stream().filter(item -> "git.commit".equals(item.name())).findFirst().orElseThrow();

        // 注册能力不代表自动放权：管理员还必须显式启用，运行时还必须逐次审批。
        assertTrue(!stage.enabled() && stage.requiresApproval());
        assertTrue(!commit.enabled() && commit.requiresApproval());
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
}
