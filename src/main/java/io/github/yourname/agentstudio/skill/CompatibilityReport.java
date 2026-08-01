package io.github.yourname.agentstudio.skill;

import java.util.List;

/** Run 准备阶段生成的、面向用户的 Skill/节点兼容报告。 */
public record CompatibilityReport(
        boolean compatible,
        List<Issue> issues,
        List<String> requiredTools,
        List<SkillAnalysis.RuntimeRequirement> requiredRuntimes,
        List<String> requiredFeatures) {

    public CompatibilityReport {
        issues = issues == null ? List.of() : List.copyOf(issues);
        requiredTools = requiredTools == null ? List.of() : List.copyOf(requiredTools);
        requiredRuntimes = requiredRuntimes == null ? List.of() : List.copyOf(requiredRuntimes);
        requiredFeatures = requiredFeatures == null ? List.of() : List.copyOf(requiredFeatures);
    }

    public record Issue(String severity, String code, String skillId, String message) {
    }
}
