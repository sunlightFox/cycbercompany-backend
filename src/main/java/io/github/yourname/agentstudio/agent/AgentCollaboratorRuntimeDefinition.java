package io.github.yourname.agentstudio.agent;

/** Immutable collaborator snapshot resolved from a published Agent manifest. */
public record AgentCollaboratorRuntimeDefinition(
        String agentId,
        String agentVersionId,
        String agentManifestDigest,
        String displayName,
        String mode,
        String when,
        String systemPrompt,
        String promptDigest,
        String defaultModelProfileId) {

    public AgentCollaboratorRuntimeDefinition {
        agentVersionId = agentVersionId == null ? "" : agentVersionId;
        agentManifestDigest = agentManifestDigest == null ? "" : agentManifestDigest;
        displayName = displayName == null || displayName.isBlank() ? agentId : displayName;
        when = when == null ? "" : when;
        systemPrompt = systemPrompt == null ? "" : systemPrompt;
        promptDigest = promptDigest == null ? "" : promptDigest;
        defaultModelProfileId = defaultModelProfileId == null ? "" : defaultModelProfileId;
    }

    public boolean supported() {
        return asTool() || handoff();
    }

    public boolean asTool() {
        return "AS_TOOL".equals(mode);
    }

    public boolean handoff() {
        return "HANDOFF".equals(mode);
    }
}
