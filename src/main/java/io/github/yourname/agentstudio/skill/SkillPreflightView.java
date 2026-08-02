package io.github.yourname.agentstudio.skill;

import io.github.yourname.agentstudio.tool.ResolvedToolBinding;
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
        skillBindings = skillBindings == null ? List.of() : List.copyOf(skillBindings);
        analyses = analyses == null ? List.of() : List.copyOf(analyses);
        effectiveTools = effectiveTools == null ? List.of() : List.copyOf(effectiveTools);
    }
}
