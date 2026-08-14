package io.github.yourname.cycbercompany.agent;

import java.util.List;
import io.github.yourname.cycbercompany.tool.AgentApprovalPolicy;

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
        List<AgentCollaboratorRuntimeDefinition> collaborators,
        String memoryPolicyJson,
        AgentApprovalPolicy approvalPolicy,
        boolean enabled) {

    public AgentRuntimeDefinition {
        agentVersionId = agentVersionId == null ? "" : agentVersionId;
        agentManifestDigest = agentManifestDigest == null ? "" : agentManifestDigest;
        skillIds = skillIds == null ? List.of() : List.copyOf(skillIds);
        knowledgeBaseIds = knowledgeBaseIds == null ? List.of() : List.copyOf(knowledgeBaseIds);
        mcpConnectionIds = mcpConnectionIds == null ? List.of() : List.copyOf(mcpConnectionIds);
        collaborators = collaborators == null ? List.of() : List.copyOf(collaborators);
        memoryPolicyJson = memoryPolicyJson == null || memoryPolicyJson.isBlank() ? "{}" : memoryPolicyJson;
        approvalPolicy = approvalPolicy == null ? AgentApprovalPolicy.sessionOnly() : approvalPolicy;
    }

    /** Compatibility constructor for callers created before collaborator snapshots were added. */
    public AgentRuntimeDefinition(
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
        this(
                agentId,
                agentVersionId,
                agentManifestDigest,
                systemPrompt,
                promptDigest,
                toolAllowList,
                defaultModelProfileId,
                skillIds,
                knowledgeBaseIds,
                mcpConnectionIds,
                List.of(),
                memoryPolicyJson,
                AgentApprovalPolicy.sessionOnly(),
                enabled);
    }

    /** Compatibility constructor for callers created after collaborator snapshots but before approval snapshots. */
    public AgentRuntimeDefinition(
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
            List<AgentCollaboratorRuntimeDefinition> collaborators,
            String memoryPolicyJson,
            boolean enabled) {
        this(agentId, agentVersionId, agentManifestDigest, systemPrompt, promptDigest, toolAllowList,
                defaultModelProfileId, skillIds, knowledgeBaseIds, mcpConnectionIds, collaborators,
                memoryPolicyJson, AgentApprovalPolicy.sessionOnly(), enabled);
    }

    public boolean versioned() {
        return !agentVersionId.isBlank();
    }
}
