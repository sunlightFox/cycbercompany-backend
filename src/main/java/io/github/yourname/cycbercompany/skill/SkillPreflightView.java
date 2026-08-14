package io.github.yourname.cycbercompany.skill;

import io.github.yourname.cycbercompany.tool.ResolvedToolBinding;
import java.util.ArrayList;
import java.util.List;

/** UI-facing readiness report generated without queuing a Run or invoking a model. */
public record SkillPreflightView(
        boolean ready,
        String agentId,
        String nodeId,
        List<SkillRunBinding> skillBindings,
        List<SkillAnalysis> analyses,
        CompatibilityReport compatibility,
        List<ResolvedToolBinding> effectiveTools) {

    public SkillPreflightView {
        skillBindings = copyNonNull(skillBindings);
        analyses = copyNonNull(analyses);
        effectiveTools = copyNonNull(effectiveTools);
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
