package io.github.yourname.agentstudio.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentRuntimeDefinitionService {

    private final AgentIdentityRepository identities;
    private final AgentVersionRepository versions;
    private final AgentCatalog legacyAgents;
    private final ObjectMapper objectMapper;

    public AgentRuntimeDefinitionService(
            AgentIdentityRepository identities,
            AgentVersionRepository versions,
            AgentCatalog legacyAgents,
            ObjectMapper objectMapper) {
        this.identities = identities;
        this.versions = versions;
        this.legacyAgents = legacyAgents;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public AgentRuntimeDefinition resolve(String agentId, String tenantId, String userId) {
        var identity = identities.findByIdAndTenantId(agentId, tenantId);
        if (identity.isPresent()) {
            AgentIdentityEntity value = identity.get();
            if ("PRIVATE".equals(value.visibility()) && !value.ownerUserId().equals(userId)) {
                throw new IllegalArgumentException("Agent not found: " + agentId);
            }
            if (!"ACTIVE".equals(value.status())) {
                throw new IllegalArgumentException("Agent is not active: " + agentId);
            }
            if (value.currentPublishedVersionId() == null || value.currentPublishedVersionId().isBlank()) {
                throw new IllegalArgumentException("Agent has no published version: " + agentId);
            }
            AgentVersionEntity version = versions.findByIdAndAgentIdAndTenantId(
                            value.currentPublishedVersionId(), agentId, tenantId)
                    .orElseThrow(() -> new IllegalStateException("Published Agent version is missing: " + agentId));
            if (version.state() != AgentVersionState.PUBLISHED) {
                throw new IllegalStateException("Current Agent version is not published: " + version.id());
            }
            var manifest = manifest(version);
            return new AgentRuntimeDefinition(
                    agentId,
                    version.id(),
                    version.manifestDigest(),
                    version.compiledSystemPrompt(),
                    version.compiledPromptDigest(),
                    version.toolAllowList(),
                    version.defaultModelProfileId(),
                    referenceIds(manifest, "skills"),
                    referenceIds(manifest, "knowledgeBases"),
                    referenceIds(manifest, "mcpConnections"),
                    manifest.path("memory").toString(),
                    true);
        }
        if (identities.existsById(agentId)) {
            throw new IllegalArgumentException("Agent not found: " + agentId);
        }

        AgentDefinitionView legacy = legacyAgents.get(agentId);
        return new AgentRuntimeDefinition(
                legacy.id(),
                "",
                "",
                legacy.systemPrompt(),
                AgentManifestCompiler.digest(legacy.systemPrompt()),
                legacy.toolAllowList(),
                legacy.defaultModelProfileId(),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                "{}",
                legacy.enabled());
    }

    private com.fasterxml.jackson.databind.JsonNode manifest(AgentVersionEntity version) {
        try {
            return objectMapper.readTree(version.manifestJson());
        } catch (Exception ex) {
            throw new IllegalStateException("Published Agent manifest is unreadable: " + version.id(), ex);
        }
    }

    private static java.util.List<String> referenceIds(
            com.fasterxml.jackson.databind.JsonNode manifest, String field) {
        java.util.List<String> values = new java.util.ArrayList<>();
        manifest.path("capabilities").path(field).forEach(reference -> values.add(reference.path("id").asText()));
        return java.util.List.copyOf(values);
    }
}
