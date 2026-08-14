package io.github.yourname.cycbercompany.skill;

import java.util.ArrayList;
import java.util.List;

/** Run 准备阶段生成的、面向用户的 Skill/节点兼容报告。 */
public record CompatibilityReport(
        boolean compatible,
        List<Issue> issues,
        List<String> requiredTools,
        List<SkillAnalysis.RuntimeRequirement> requiredRuntimes,
        List<String> requiredFeatures) {

    public CompatibilityReport {
        issues = copyNonNull(issues);
        requiredTools = copyNonNull(requiredTools);
        requiredRuntimes = copyNonNull(requiredRuntimes);
        requiredFeatures = copyNonNull(requiredFeatures);
    }

    public record Issue(String severity, String code, String skillId, String message) {
    }

    private static <T> List<T> copyNonNull(List<T> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<T> sanitized = new ArrayList<>();
        for (T value : values) {
            if (value != null) {
                sanitized.add(value);
            }
        }
        return sanitized.isEmpty() ? List.of() : List.copyOf(sanitized);
    }
}
