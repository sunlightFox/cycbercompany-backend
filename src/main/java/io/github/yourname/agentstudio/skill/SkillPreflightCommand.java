package io.github.yourname.agentstudio.skill;

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
        skillIds = skillIds == null ? List.of() : List.copyOf(skillIds);
        knowledgeBaseIds = knowledgeBaseIds == null ? List.of() : List.copyOf(knowledgeBaseIds);
        mcpServerIds = mcpServerIds == null ? List.of() : List.copyOf(mcpServerIds);
        toolNames = toolNames == null ? List.of() : List.copyOf(toolNames);
    }
}
