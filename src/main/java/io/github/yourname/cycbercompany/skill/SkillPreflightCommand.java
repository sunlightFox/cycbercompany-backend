package io.github.yourname.cycbercompany.skill;

import java.util.ArrayList;
import java.util.List;

/** Inputs needed to assess whether selected Skills can run in the current capability scope. */
public record SkillPreflightCommand(
        List<String> skillIds,
        String agentId,
        String nodeId,
        List<String> knowledgeBaseIds,
        List<String> mcpServerIds,
        List<String> toolNames) {

    public SkillPreflightCommand {
        skillIds = copyNonNull(skillIds);
        knowledgeBaseIds = copyNonNull(knowledgeBaseIds);
        mcpServerIds = copyNonNull(mcpServerIds);
        toolNames = copyNonNull(toolNames);
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
