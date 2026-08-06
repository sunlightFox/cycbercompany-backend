package io.github.yourname.agentstudio.skill;

import java.util.ArrayList;
import java.util.List;

/** 不执行 Skill 的静态分析结果，可直接持久化到 RunSpec。 */
public record SkillAnalysis(
        String skillId,
        int level,
        List<String> declaredTools,
        List<String> requiredTools,
        List<RuntimeRequirement> runtimes,
        List<String> requiredFeatures,
        String network,
        List<String> resources,
        List<String> scripts,
        List<String> warnings) {

    public SkillAnalysis {
        declaredTools = copy(declaredTools);
        requiredTools = copy(requiredTools);
        runtimes = copy(runtimes);
        requiredFeatures = copy(requiredFeatures);
        resources = copy(resources);
        scripts = copy(scripts);
        warnings = copy(warnings);
        network = network == null || network.isBlank() ? "none" : network;
    }

    private static <T> List<T> copy(List<T> values) {
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

    public record RuntimeRequirement(String name, String versionConstraint, String source) {
    }
}
