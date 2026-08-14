package io.github.yourname.cycbercompany.skill;

import io.github.yourname.cycbercompany.node.NodeDetailView;
import io.github.yourname.cycbercompany.tool.ResolvedToolBinding;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/** 把 Skill 的要求与本次 Run 已授权工具及已选节点事实做交叉检查。 */
@Service
public class SkillCompatibilityService {

    public CompatibilityReport check(
            List<SkillAnalysis> analyses,
            List<ResolvedToolBinding> bindings,
            NodeDetailView node) {
        Set<String> availableTools = new LinkedHashSet<>();
        if (bindings != null) {
            bindings.forEach(binding -> availableTools.add(binding.logicalName()));
        }
        Map<String, String> runtimes = node == null ? Map.of() : node.node().runtimeVersions();
        Set<String> features = node == null ? Set.of() : node.node().features();
        List<CompatibilityReport.Issue> issues = new ArrayList<>();
        Set<String> requiredTools = new LinkedHashSet<>();
        List<SkillAnalysis.RuntimeRequirement> requiredRuntimes = new ArrayList<>();
        Set<String> requiredFeatures = new LinkedHashSet<>();

        for (SkillAnalysis analysis : analyses == null ? List.<SkillAnalysis>of() : analyses) {
            requiredTools.addAll(analysis.requiredTools());
            requiredRuntimes.addAll(analysis.runtimes());
            requiredFeatures.addAll(analysis.requiredFeatures());
            analysis.warnings().forEach(warning -> issues.add(new CompatibilityReport.Issue(
                    "ERROR",
                    warning.startsWith("Unsupported script runtime:") ? "UNSUPPORTED_SCRIPT_RUNTIME" : "UNKNOWN_TOOL_ALIAS",
                    analysis.skillId(),
                    warning)));
            analysis.requiredTools().stream()
                    .filter(tool -> !availableTools.contains(tool))
                    .forEach(tool -> issues.add(new CompatibilityReport.Issue(
                            "ERROR", "MISSING_TOOL", analysis.skillId(),
                            "Skill requires tool '" + tool + "', but it is not in the effective Agent/Run tool set.")));
            analysis.runtimes().stream()
                    .filter(requirement -> !runtimes.containsKey(requirement.name()))
                    .forEach(requirement -> issues.add(new CompatibilityReport.Issue(
                            "ERROR", "MISSING_RUNTIME", analysis.skillId(),
                            "Skill requires runtime '" + requirement.name() + "'"
                                    + constraint(requirement.versionConstraint()) + ", but the selected node did not report it.")));
            analysis.runtimes().stream()
                    .filter(requirement -> runtimes.containsKey(requirement.name()))
                    .filter(requirement -> !satisfies(
                            runtimes.get(requirement.name()), requirement.versionConstraint()))
                    .forEach(requirement -> issues.add(new CompatibilityReport.Issue(
                            "ERROR", "RUNTIME_VERSION_MISMATCH", analysis.skillId(),
                            "Skill requires runtime '" + requirement.name() + "'"
                                    + constraint(requirement.versionConstraint()) + ", but node reported "
                                    + runtimes.get(requirement.name()) + ".")));
            analysis.requiredFeatures().stream()
                    .filter(feature -> !features.contains(feature))
                    .forEach(feature -> issues.add(new CompatibilityReport.Issue(
                            "ERROR", "MISSING_FEATURE", analysis.skillId(),
                            "Skill requires node feature '" + feature + "', but the selected node did not report it.")));
            if (analysis.network() != null && !analysis.network().isBlank()
                    && !"none".equalsIgnoreCase(analysis.network())) {
                issues.add(new CompatibilityReport.Issue(
                        "ERROR", "NETWORK_UNSUPPORTED", analysis.skillId(),
                        "Skill declares network='" + analysis.network()
                                + "', but the available Docker Skill runtime supports only network='none'."));
            }
        }
        boolean compatible = issues.stream().noneMatch(issue -> "ERROR".equals(issue.severity()));
        return new CompatibilityReport(
                compatible,
                issues,
                List.copyOf(requiredTools),
                List.copyOf(requiredRuntimes),
                List.copyOf(requiredFeatures));
    }

    private static String constraint(String value) {
        return value == null || value.isBlank() ? "" : " " + value;
    }

    /** 支持 Skill 常见的单边界版本约束；无法解析时保守地判定不满足。 */
    static boolean satisfies(String actual, String constraint) {
        if (constraint == null || constraint.isBlank() || actual == null || actual.isBlank()) {
            return actual != null && !actual.isBlank();
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("^(>=|<=|>|<|=|==|~)?\\s*([0-9]+(?:\\.[0-9]+){0,3}).*$")
                .matcher(constraint.trim());
        if (!matcher.matches()) {
            return false;
        }
        int comparison = compareVersions(actual, matcher.group(2));
        return switch (matcher.group(1) == null ? ">=" : matcher.group(1)) {
            case ">" -> comparison > 0;
            case ">=" -> comparison >= 0;
            case "<" -> comparison < 0;
            case "<=" -> comparison <= 0;
            case "=", "==" -> comparison == 0;
            case "~" -> sameMajorMinor(actual, matcher.group(2)) && comparison >= 0;
            default -> false;
        };
    }

    private static int compareVersions(String left, String right) {
        int[] a = versionParts(left);
        int[] b = versionParts(right);
        for (int index = 0; index < Math.max(a.length, b.length); index++) {
            int av = index < a.length ? a[index] : 0;
            int bv = index < b.length ? b[index] : 0;
            if (av != bv) return Integer.compare(av, bv);
        }
        return 0;
    }

    private static boolean sameMajorMinor(String left, String right) {
        int[] a = versionParts(left);
        int[] b = versionParts(right);
        return a.length > 1 && b.length > 1 && a[0] == b[0] && a[1] == b[1];
    }

    private static int[] versionParts(String value) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("([0-9]+(?:\\.[0-9]+){0,3})")
                .matcher(value == null ? "" : value);
        if (!matcher.find()) return new int[0];
        return java.util.Arrays.stream(matcher.group(1).split("\\."))
                .mapToInt(Integer::parseInt).toArray();
    }
}
