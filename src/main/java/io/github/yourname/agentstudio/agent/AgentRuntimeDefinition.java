package io.github.yourname.agentstudio.agent;

import java.util.List;

public record AgentRuntimeDefinition(
        String agentId,
        String agentVersionId,
        String agentManifestDigest,
        String systemPrompt,
        String promptDigest,
        String toolAllowList,
        String defaultModelProfileId,
        List<String> skillIds,
        List<String> knowledgeBaseIds,
        List<String> mcpConnectionIds,
        String memoryPolicyJson,
        boolean enabled) {

    public AgentRuntimeDefinition {
        agentVersionId = agentVersionId == null ? "" : agentVersionId;
        agentManifestDigest = agentManifestDigest == null ? "" : agentManifestDigest;
        skillIds = skillIds == null ? List.of() : List.copyOf(skillIds);
        knowledgeBaseIds = knowledgeBaseIds == null ? List.of() : List.copyOf(knowledgeBaseIds);
        mcpConnectionIds = mcpConnectionIds == null ? List.of() : List.copyOf(mcpConnectionIds);
        memoryPolicyJson = memoryPolicyJson == null || memoryPolicyJson.isBlank() ? "{}" : memoryPolicyJson;
    }

    public boolean versioned() {
        return !agentVersionId.isBlank();
    }
}
