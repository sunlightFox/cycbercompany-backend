package io.github.yourname.agentstudio.skill;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SkillPreflightNullSafetyTest {

    @Test
    void preflightCommandTreatsNullListEntriesAsOmitted() {
        List<String> skillIds = new ArrayList<>();
        skillIds.add("skill-a");
        skillIds.add(null);
        List<String> tools = new ArrayList<>();
        tools.add(null);
        tools.add("system.shell.run");

        SkillPreflightCommand command = new SkillPreflightCommand(
                skillIds, "agent-1", "node-1", null, List.of(), tools);

        assertThat(command.skillIds()).containsExactly("skill-a");
        assertThat(command.knowledgeBaseIds()).isEmpty();
        assertThat(command.toolNames()).containsExactly("system.shell.run");
    }

    @Test
    void preflightViewTreatsNullListEntriesAsOmitted() {
        List<SkillRunBinding> bindings = new ArrayList<>();
        bindings.add(binding());
        bindings.add(null);
        List<SkillAnalysis> analyses = new ArrayList<>();
        analyses.add(null);
        analyses.add(analysis());

        SkillPreflightView view = new SkillPreflightView(
                true,
                "agent-1",
                "node-1",
                bindings,
                analyses,
                null,
                new ArrayList<>());

        assertThat(view.skillBindings()).containsExactly(binding());
        assertThat(view.analyses()).containsExactly(analysis());
        assertThat(view.effectiveTools()).isEmpty();
    }

    @Test
    void analysisAndCompatibilityReportTreatNullListEntriesAsOmitted() {
        List<String> requiredTools = new ArrayList<>();
        requiredTools.add("system.shell.run");
        requiredTools.add(null);
        List<CompatibilityReport.Issue> issues = new ArrayList<>();
        issues.add(new CompatibilityReport.Issue("WARN", "MISSING_OPTIONAL", "skill-a", "Optional tool missing"));
        issues.add(null);

        SkillAnalysis analysis = new SkillAnalysis(
                "skill-a",
                1,
                requiredTools,
                requiredTools,
                List.of(),
                requiredTools,
                null,
                requiredTools,
                requiredTools,
                requiredTools);
        CompatibilityReport report = new CompatibilityReport(
                true, issues, requiredTools, List.of(), requiredTools);

        assertThat(analysis.requiredTools()).containsExactly("system.shell.run");
        assertThat(analysis.network()).isEqualTo("none");
        assertThat(report.issues()).containsExactly(issues.getFirst());
        assertThat(report.requiredFeatures()).containsExactly("system.shell.run");
    }

    private static SkillRunBinding binding() {
        return new SkillRunBinding(
                "skill-a",
                "Skill A",
                "Test skill",
                "sha256:skill",
                null,
                null,
                null,
                null,
                null);
    }

    private static SkillAnalysis analysis() {
        return new SkillAnalysis(
                "skill-a",
                1,
                List.of(),
                List.of("system.shell.run"),
                List.of(),
                List.of(),
                "none",
                List.of(),
                List.of(),
                List.of());
    }
}
