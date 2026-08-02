package io.github.yourname.agentstudio.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yourname.agentstudio.tool.ResolvedToolBinding;
import io.github.yourname.agentstudio.tool.RiskLevel;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NodeTaskPolicyTest {

    @Test
    void desktopOrganizationExposesOnlyTheRequiredFileTools() {
        NodeTaskPolicy policy = NodeTaskPolicy.from(command("Help me organize my desktop"));

        List<ResolvedToolBinding> filtered = policy.filter(List.of(
                tool("system.desktop.organize.list"),
                tool("system.desktop.organize.mkdir"),
                tool("system.desktop.organize.write"),
                tool("system.desktop.organize.move"),
                tool("system.desktop.organize.delete"),
                tool("system.fs.list"),
                tool("system.fs.mkdir"),
                tool("system.fs.move"),
                tool("system.desktop.set_wallpaper"),
                tool("system.shell.run"),
                tool("local_time")));

        assertThat(filtered).extracting(ResolvedToolBinding::logicalName)
                .containsExactlyInAnyOrder(
                        "system.desktop.organize.list",
                        "system.desktop.organize.mkdir",
                        "system.desktop.organize.write",
                        "system.desktop.organize.move",
                        "system.desktop.organize.delete");
        assertThat(policy.requiresFirstTool("system.desktop.organize.list")).isTrue();
        assertThat(policy.requiresFirstTool("system.desktop.organize.move")).isFalse();
    }

    @Test
    void ordinaryNodeTasksKeepTheirResolvedTools() {
        NodeTaskPolicy policy = NodeTaskPolicy.from(command("Review this repository"));
        List<ResolvedToolBinding> bindings = List.of(tool("fs.read"), tool("system.shell.run"));

        assertThat(policy.filter(bindings)).containsExactlyElementsOf(bindings);
    }

    @Test
    void recognizesChineseDesktopCleanupAsDesktopOrganization() {
        NodeTaskPolicy policy = NodeTaskPolicy.from(command("清理桌面上的文件"));

        assertThat(policy.isRestricted()).isTrue();
        assertThat(policy.requiresFirstTool("system.desktop.organize.list")).isTrue();
    }

    @Test
    void treatsAnExplicitFileDeletionAsScopedDesktopWork() {
        NodeTaskPolicy policy = NodeTaskPolicy.from(command("Delete e2e-conflict.txt"));

        assertThat(policy.isRestricted()).isTrue();
        assertThat(policy.requiresFirstTool("system.desktop.organize.list")).isTrue();
        assertThat(policy.permits("system.desktop.organize.delete")).isTrue();
        assertThat(policy.permits("system.fs.delete")).isFalse();
    }

    @Test
    void treatsAnExplicitDesktopTextFileCreationAsScopedDesktopWork() {
        NodeTaskPolicy policy = NodeTaskPolicy.from(command("\u5728\u684c\u9762\u6839\u76ee\u5f55\u521b\u5efa \u9759\u591c\u601d.txt"));

        assertThat(policy.isRestricted()).isTrue();
        assertThat(policy.requiresFirstTool("system.desktop.organize.list")).isTrue();
        assertThat(policy.permits("system.desktop.organize.write")).isTrue();
        assertThat(policy.permits("system.fs.write")).isFalse();
    }

    @Test
    void requiresApiEvidenceOnlyWhenTheRequestExplicitlyCallsForFrontendBackendIntegration() {
        NodeTaskPolicy fullStack = NodeTaskPolicy.from(command("请编写一个前后端项目并完成联调"));
        NodeTaskPolicy ordinary = NodeTaskPolicy.from(command("Build a static landing page"));

        assertThat(fullStack.requiresFullStackApiEvidence()).isTrue();
        assertThat(ordinary.requiresFullStackApiEvidence()).isFalse();
    }

    private static CreateRunCommand command(String text) {
        return new CreateRunCommand(
                "conversation-1", text, "model-1", "agent-1", List.of(), List.of(), List.of(), List.of(), "node-1", null);
    }

    private static ResolvedToolBinding tool(String logicalName) {
        return new ResolvedToolBinding(
                "node:node-1:" + logicalName,
                "tool_" + logicalName.replace('.', '_'),
                logicalName,
                "node",
                logicalName,
                logicalName,
                RiskLevel.HIGH,
                true,
                Map.of("type", "object"),
                Map.of("nodeId", "node-1"));
    }
}
