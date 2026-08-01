package io.github.yourname.agentstudio.skill;

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
        return values == null ? List.of() : List.copyOf(values);
    }

    public record RuntimeRequirement(String name, String versionConstraint, String source) {
    }
}
