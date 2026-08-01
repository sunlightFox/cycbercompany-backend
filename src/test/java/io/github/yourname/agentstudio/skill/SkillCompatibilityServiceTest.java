package io.github.yourname.agentstudio.skill;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yourname.agentstudio.node.NodeConnectionView;
import io.github.yourname.agentstudio.node.NodeDetailView;
import io.github.yourname.agentstudio.node.NodeStatus;
import io.github.yourname.agentstudio.tool.ResolvedToolBinding;
import io.github.yourname.agentstudio.tool.RiskLevel;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SkillCompatibilityServiceTest {

    @Test
    void reportsMissingToolRuntimeAndFeatureBeforeRunStarts() {
        SkillAnalysis analysis = new SkillAnalysis(
                "python-skill",
                3,
                List.of("Read", "Bash"),
                List.of("fs.read", "shell.run"),
                List.of(new SkillAnalysis.RuntimeRequirement("python", ">=3.11", "frontmatter")),
                List.of("skill.script.runtime.v1"),
                "none",
                List.of(),
                List.of("scripts/run.py"),
                List.of());
        ResolvedToolBinding read = binding("fs.read");
        NodeDetailView node = node(Map.of("java", "21"), Set.of("workspace.scope.v1"));

        CompatibilityReport report = new SkillCompatibilityService().check(
                List.of(analysis), List.of(read), node);

        assertThat(report.compatible()).isFalse();
        assertThat(report.issues()).extracting(CompatibilityReport.Issue::code)
                .contains("MISSING_TOOL", "MISSING_RUNTIME", "MISSING_FEATURE");
    }

    @Test
    void acceptsRequirementsOnlyWhenTheyAreAlreadyInsideTheEffectiveBindingSet() {
        SkillAnalysis analysis = new SkillAnalysis(
                "python-skill", 3, List.of("Read", "Bash"), List.of("fs.read", "shell.run"),
                List.of(new SkillAnalysis.RuntimeRequirement("python", ">=3.11", "frontmatter")),
                List.of("skill.script.runtime.v1"), "none", List.of(), List.of("scripts/run.py"), List.of());
        NodeDetailView node = node(
                Map.of("java", "21", "python", "3.12.2"),
                Set.of("workspace.scope.v1", "skill.script.runtime.v1"));

        CompatibilityReport report = new SkillCompatibilityService().check(
                List.of(analysis), List.of(binding("fs.read"), binding("shell.run")), node);

        assertThat(report.compatible()).isTrue();
        assertThat(report.issues()).isEmpty();
    }

    @Test
    void rejectsAnInstalledRuntimeBelowTheDeclaredVersion() {
        SkillAnalysis analysis = new SkillAnalysis(
                "modern-python", 1, List.of(), List.of(),
                List.of(new SkillAnalysis.RuntimeRequirement("python", ">=3.11", "frontmatter")),
                List.of(), "none", List.of(), List.of(), List.of());

        CompatibilityReport report = new SkillCompatibilityService().check(
                List.of(analysis), List.of(), node(Map.of("python", "3.10.14"), Set.of()));

        assertThat(report.compatible()).isFalse();
        assertThat(report.issues()).extracting(CompatibilityReport.Issue::code)
                .contains("RUNTIME_VERSION_MISMATCH");
    }

    private static ResolvedToolBinding binding(String name) {
        return new ResolvedToolBinding(
                "node:node-1:" + name, "tool_" + name.replace('.', '_'), name, "node", name,
                name, RiskLevel.LOW, false, Map.of("type", "object"), Map.of("nodeId", "node-1"));
    }

    private static NodeDetailView node(Map<String, String> runtimes, Set<String> features) {
        Instant now = Instant.now();
        return new NodeDetailView(new NodeConnectionView(
                "node-1", "Node", "host", "Windows", "amd64", "1.0",
                "sha256:revision", runtimes, features, true, NodeStatus.ONLINE, now, now, now), List.of());
    }
}
