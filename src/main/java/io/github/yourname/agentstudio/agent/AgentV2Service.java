package io.github.yourname.agentstudio.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentV2Service {

    private final AgentIdentityRepository identities;
    private final AgentVersionRepository versions;
    private final AgentDefinitionRepository legacyAgents;
    private final AgentManifestCompiler compiler;
    private final ObjectMapper objectMapper;
    private final AgentEvaluationGate evaluationGate;
    private final AgentCapabilityValidator capabilityValidator;

    public AgentV2Service(
            AgentIdentityRepository identities,
            AgentVersionRepository versions,
            AgentDefinitionRepository legacyAgents,
            AgentManifestCompiler compiler,
            ObjectMapper objectMapper,
            AgentEvaluationGate evaluationGate,
            AgentCapabilityValidator capabilityValidator) {
        this.identities = identities;
        this.versions = versions;
        this.legacyAgents = legacyAgents;
        this.compiler = compiler;
        this.objectMapper = objectMapper;
        this.evaluationGate = evaluationGate;
        this.capabilityValidator = capabilityValidator;
    }

    @Transactional(readOnly = true)
    public List<AgentV2View> list(String tenantId, String userId) {
        return identities.findAllByTenantIdOrderByUpdatedAtDesc(tenantId).stream()
                .filter(identity -> !"ARCHIVED".equals(identity.status()))
                .filter(identity -> visibleTo(identity, userId))
                .map(this::view)
                .toList();
    }

    @Transactional(readOnly = true)
    public AgentV2View get(String agentId, String tenantId, String userId) {
        return view(requireVisibleIdentity(agentId, tenantId, userId));
    }

    @Transactional
    public AgentV2View create(CreateAgentV2Command command, String tenantId, String userId) {
        JsonNode manifest = manifestNode(command.manifest());
        AgentManifestCompiler.CompiledManifest compiled = compiler.compile(manifest);
        JsonNode identityNode = manifest.path("identity");
        String agentId = UUID.randomUUID().toString();
        rejectSelfCollaboration(agentId, manifest);
        Instant now = Instant.now();
        AgentIdentityEntity identity = new AgentIdentityEntity(
                agentId,
                tenantId,
                userId,
                identityNode.path("displayName").asText(),
                identityNode.path("description").asText(""),
                identityNode.path("avatarRef").asText(""),
                identityNode.path("category").asText(""),
                tagsJson(identityNode.path("tags")),
                normalizeVisibility(command.visibility()),
                now);
        AgentVersionEntity draft = new AgentVersionEntity(
                UUID.randomUUID().toString(), agentId, tenantId, 1, compiled, userId, now);
        identities.save(identity);
        versions.saveAndFlush(draft);
        return view(identity);
    }

    @Transactional
    public AgentVersionView createDraft(String agentId, String tenantId, String userId) {
        AgentIdentityEntity identity = requireOwnedIdentity(agentId, tenantId, userId);
        var existingDraft = versions.findTopByAgentIdAndTenantIdAndStateOrderByVersionNumberDesc(
                agentId, tenantId, AgentVersionState.DRAFT.name());
        if (existingDraft.isPresent()) {
            return versionView(existingDraft.get());
        }
        AgentVersionEntity source = latestVersion(identity);
        AgentManifestCompiler.CompiledManifest compiled = compiler.compile(parse(source.manifestJson()));
        AgentVersionEntity draft = new AgentVersionEntity(
                UUID.randomUUID().toString(),
                agentId,
                tenantId,
                source.versionNumber() + 1,
                compiled,
                userId,
                Instant.now());
        return versionView(versions.save(draft));
    }

    @Transactional
    public AgentVersionView updateDraft(
            String agentId,
            String versionId,
            UpdateAgentManifestCommand command,
            String tenantId,
            String userId) {
        AgentIdentityEntity identity = requireOwnedIdentity(agentId, tenantId, userId);
        AgentVersionEntity draft = requireVersion(agentId, versionId, tenantId);
        if (command.expectedRevision() != null && command.expectedRevision() != draft.revision()) {
            throw new AgentRevisionConflictException(versionId, command.expectedRevision(), draft.revision());
        }
        JsonNode manifest = manifestNode(command.manifest());
        AgentManifestCompiler.CompiledManifest compiled = compiler.compile(manifest);
        rejectSelfCollaboration(agentId, manifest);
        draft.replaceManifest(compiled);
        versions.saveAndFlush(draft);
        if (identity.currentPublishedVersionId() == null) {
            updateIdentityFromManifest(identity, manifest, identity.visibility(), Instant.now());
            identities.save(identity);
        }
        return versionView(draft);
    }

    @Transactional(readOnly = true)
    public AgentManifestValidationView validateDraft(
            String agentId, String versionId, String tenantId, String userId) {
        requireOwnedIdentity(agentId, tenantId, userId);
        AgentVersionEntity draft = requireVersion(agentId, versionId, tenantId);
        JsonNode manifest = parse(draft.manifestJson());
        AgentManifestCompiler.ManifestValidation validation = compiler.validate(manifest);
        if (!validation.valid()) {
            return new AgentManifestValidationView(false, validation.errors(), null, null);
        }
        List<String> capabilityErrors = capabilityValidator.validate(manifest, tenantId, userId);
        if (!capabilityErrors.isEmpty()) {
            return new AgentManifestValidationView(false, capabilityErrors, null, null);
        }
        List<String> collaborationErrors = collaborationErrors(agentId, manifest, tenantId, userId);
        if (!collaborationErrors.isEmpty()) {
            return new AgentManifestValidationView(false, collaborationErrors, null, null);
        }
        AgentManifestCompiler.CompiledManifest compiled = compiler.compile(manifest);
        return new AgentManifestValidationView(
                true, List.of(), compiled.manifestDigest(), compiled.promptDigest());
    }

    @Transactional
    public AgentVersionView publish(String agentId, String versionId, String tenantId, String userId) {
        AgentIdentityEntity identity = requireOwnedIdentity(agentId, tenantId, userId);
        AgentVersionEntity draft = requireVersion(agentId, versionId, tenantId);
        if (draft.state() == AgentVersionState.PUBLISHED
                && versionId.equals(identity.currentPublishedVersionId())) {
            return versionView(draft);
        }
        if (draft.state() != AgentVersionState.DRAFT) {
            throw new IllegalArgumentException("Only a draft Agent version can be published: " + versionId);
        }
        JsonNode manifest = parse(draft.manifestJson());
        rejectSelfCollaboration(agentId, manifest);
        List<String> capabilityErrors = capabilityValidator.validate(manifest, tenantId, userId);
        if (!capabilityErrors.isEmpty()) {
            throw new AgentManifestValidationException(capabilityErrors);
        }
        List<String> collaborationErrors = collaborationErrors(agentId, manifest, tenantId, userId);
        if (!collaborationErrors.isEmpty()) {
            throw new AgentManifestValidationException(collaborationErrors);
        }
        AgentManifestCompiler.CompiledManifest compiled = compiler.compile(manifest);
        draft.replaceManifest(compiled);
        evaluationGate.verify(draft, manifest);
        Instant now = Instant.now();
        draft.publish(now);
        versions.saveAndFlush(draft);
        updateIdentityFromManifest(identity, manifest, identity.visibility(), now);
        identity.publish(draft.id(), now);
        identities.save(identity);
        upsertLegacyDefinition(identity, draft, now);
        return versionView(draft);
    }

    @Transactional(readOnly = true)
    public List<AgentVersionView> versions(String agentId, String tenantId, String userId) {
        requireVisibleIdentity(agentId, tenantId, userId);
        return versions.findAllByAgentIdAndTenantIdOrderByVersionNumberDesc(agentId, tenantId).stream()
                .map(this::versionView)
                .toList();
    }

    @Transactional(readOnly = true)
    public AgentVersionView version(String agentId, String versionId, String tenantId, String userId) {
        requireVisibleIdentity(agentId, tenantId, userId);
        return versionView(requireVersion(agentId, versionId, tenantId));
    }

    private AgentIdentityEntity requireVisibleIdentity(String agentId, String tenantId, String userId) {
        AgentIdentityEntity identity = requireIdentity(agentId, tenantId);
        if (!visibleTo(identity, userId)) {
            throw new IllegalArgumentException("Agent not found: " + agentId);
        }
        return identity;
    }

    private static boolean visibleTo(AgentIdentityEntity identity, String userId) {
        return !"PRIVATE".equals(identity.visibility()) || identity.ownerUserId().equals(userId);
    }

    @Transactional
    public AgentV2View updateSettings(
            String agentId,
            UpdateAgentSettingsCommand command,
            String tenantId,
            String userId) {
        AgentIdentityEntity identity = requireOwnedIdentity(agentId, tenantId, userId);
        if (command.expectedRevision() != identity.revision()) {
            throw new AgentIdentityRevisionConflictException(
                    agentId, command.expectedRevision(), identity.revision());
        }
        if (command.visibility() == null && command.status() == null) {
            throw new IllegalArgumentException("At least one Agent setting must be provided.");
        }
        String visibility = command.visibility() == null
                ? identity.visibility()
                : normalizeVisibility(command.visibility());
        String status = command.status() == null
                ? identity.status()
                : normalizeStatus(command.status());
        identity.updateSettings(visibility, status, Instant.now());
        identities.saveAndFlush(identity);
        syncLegacyEnabled(identity);
        return view(identity);
    }

    @Transactional
    public AgentV2View archive(String agentId, String tenantId, String userId) {
        AgentIdentityEntity identity = requireOwnedIdentity(agentId, tenantId, userId);
        identity.archive(Instant.now());
        identities.saveAndFlush(identity);
        syncLegacyEnabled(identity);
        return view(identity);
    }

    private AgentV2View view(AgentIdentityEntity identity) {
        AgentVersionView current = null;
        if (identity.currentPublishedVersionId() != null) {
            current = versions.findByIdAndAgentIdAndTenantId(
                            identity.currentPublishedVersionId(), identity.id(), identity.tenantId())
                    .map(this::versionView)
                    .orElse(null);
        }
        AgentVersionView draft = versions.findTopByAgentIdAndTenantIdAndStateOrderByVersionNumberDesc(
                        identity.id(), identity.tenantId(), AgentVersionState.DRAFT.name())
                .map(this::versionView)
                .orElse(null);
        return new AgentV2View(
                identity.id(),
                identity.displayName(),
                identity.description(),
                identity.avatarRef(),
                identity.category(),
                parseTags(identity.tagsJson()),
                identity.visibility(),
                identity.status(),
                identity.currentPublishedVersionId(),
                identity.revision(),
                identity.createdAt(),
                identity.updatedAt(),
                current,
                draft);
    }

    private AgentVersionView versionView(AgentVersionEntity version) {
        return new AgentVersionView(
                version.id(),
                version.revision(),
                version.versionNumber(),
                version.schemaVersion(),
                version.state(),
                parseManifestMap(version.manifestJson()),
                version.manifestDigest(),
                version.compiledPromptDigest(),
                version.createdBy(),
                version.createdAt(),
                version.publishedAt());
    }

    private AgentIdentityEntity requireIdentity(String agentId, String tenantId) {
        return identities.findByIdAndTenantId(agentId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentId));
    }

    private AgentIdentityEntity requireOwnedIdentity(String agentId, String tenantId, String userId) {
        AgentIdentityEntity identity = requireIdentity(agentId, tenantId);
        if (!identity.ownerUserId().equals(userId)) {
            throw new IllegalArgumentException("Only the Agent owner can modify it: " + agentId);
        }
        if ("ARCHIVED".equals(identity.status())) {
            throw new IllegalArgumentException("Agent is archived: " + agentId);
        }
        return identity;
    }

    private AgentVersionEntity requireVersion(String agentId, String versionId, String tenantId) {
        return versions.findByIdAndAgentIdAndTenantId(versionId, agentId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Agent version not found: " + versionId));
    }

    private AgentVersionEntity latestVersion(AgentIdentityEntity identity) {
        return versions.findTopByAgentIdAndTenantIdOrderByVersionNumberDesc(identity.id(), identity.tenantId())
                .orElseThrow(() -> new IllegalStateException("Agent has no version: " + identity.id()));
    }

    private void upsertLegacyDefinition(AgentIdentityEntity identity, AgentVersionEntity version, Instant now) {
        AgentDefinitionEntity legacy = legacyAgents.findById(identity.id()).orElse(null);
        if (legacy == null) {
            legacy = new AgentDefinitionEntity(
                    identity.id(),
                    identity.displayName(),
                    identity.description(),
                    version.compiledSystemPrompt(),
                    version.defaultModelProfileId(),
                    version.toolAllowList(),
                    true,
                    now);
        } else {
            legacy.updatePublishedSnapshot(
                    identity.displayName(),
                    identity.description(),
                    version.compiledSystemPrompt(),
                    version.defaultModelProfileId(),
                    version.toolAllowList());
        }
        legacy.setEnabled("ACTIVE".equals(identity.status()));
        legacyAgents.save(legacy);
    }

    private void syncLegacyEnabled(AgentIdentityEntity identity) {
        legacyAgents.findById(identity.id()).ifPresent(legacy -> {
            legacy.setEnabled("ACTIVE".equals(identity.status()));
            legacyAgents.save(legacy);
        });
    }

    private void updateIdentityFromManifest(
            AgentIdentityEntity identity, JsonNode manifest, String visibility, Instant now) {
        JsonNode value = manifest.path("identity");
        identity.update(
                value.path("displayName").asText(),
                value.path("description").asText(""),
                value.path("avatarRef").asText(""),
                value.path("category").asText(""),
                tagsJson(value.path("tags")),
                visibility,
                now);
    }

    private static void rejectSelfCollaboration(String agentId, JsonNode manifest) {
        for (JsonNode collaborator : manifest.path("capabilities").path("collaborators")) {
            if (agentId.equals(collaborator.path("agentId").asText())) {
                throw new IllegalArgumentException("An Agent cannot collaborate with itself.");
            }
        }
    }

    private List<String> collaborationErrors(
            String agentId, JsonNode manifest, String tenantId, String userId) {
        List<String> errors = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int handoffCount = 0;
        boolean hasAsTool = false;
        for (JsonNode collaborator : manifest.path("capabilities").path("collaborators")) {
            String targetId = collaborator.path("agentId").asText("");
            if (targetId.equals(agentId)) {
                errors.add("An Agent cannot collaborate with itself.");
                continue;
            }
            if (!seen.add(targetId)) {
                errors.add("Collaborator Agent is configured more than once: " + targetId);
                continue;
            }
            String mode = collaborator.path("mode").asText();
            if ("HANDOFF".equals(mode)) handoffCount++;
            if ("AS_TOOL".equals(mode)) hasAsTool = true;
            AgentIdentityEntity target = identities.findByIdAndTenantId(targetId, tenantId).orElse(null);
            if (target == null
                    || ("PRIVATE".equals(target.visibility()) && !target.ownerUserId().equals(userId))) {
                errors.add("Collaborator Agent not found: " + targetId);
                continue;
            }
            if (!"ACTIVE".equals(target.status())) {
                errors.add("Collaborator Agent is not active: " + targetId);
                continue;
            }
            if (target.currentPublishedVersionId() == null
                    || target.currentPublishedVersionId().isBlank()) {
                errors.add("Collaborator Agent has no published version: " + targetId);
            }
        }
        if (handoffCount > 1) {
            errors.add("Only one HANDOFF collaborator may be configured.");
        }
        if (handoffCount > 0 && hasAsTool) {
            errors.add("HANDOFF cannot be combined with AS_TOOL collaborators.");
        }
        Set<String> cycleSignatures = new HashSet<>();
        for (String targetId : seen) {
            List<String> cycle = findCollaboratorCycle(
                    agentId, targetId, tenantId, userId, new HashSet<>(), new HashSet<>(), new ArrayList<>());
            if (!cycle.isEmpty()) {
                String signature = String.join(" -> ", cycle);
                if (cycleSignatures.add(signature)) {
                    errors.add("Collaborator cycle detected: " + signature);
                }
            }
        }
        return List.copyOf(errors);
    }

    private List<String> findCollaboratorCycle(
            String rootAgentId,
            String currentAgentId,
            String tenantId,
            String userId,
            Set<String> active,
            Set<String> visited,
            List<String> path) {
        if (rootAgentId.equals(currentAgentId)) {
            List<String> cycle = new ArrayList<>(path);
            cycle.add(rootAgentId);
            return List.copyOf(cycle);
        }
        if (!active.add(currentAgentId) || !visited.add(currentAgentId)) {
            return List.of();
        }
        var target = identities.findByIdAndTenantId(currentAgentId, tenantId).orElse(null);
        if (target == null || !visibleTo(target, userId)
                || !"ACTIVE".equals(target.status())
                || target.currentPublishedVersionId() == null
                || target.currentPublishedVersionId().isBlank()) {
            active.remove(currentAgentId);
            return List.of();
        }
        var version = versions.findByIdAndAgentIdAndTenantId(
                        target.currentPublishedVersionId(), currentAgentId, tenantId)
                .orElse(null);
        if (version == null || version.state() != AgentVersionState.PUBLISHED) {
            active.remove(currentAgentId);
            return List.of();
        }
        JsonNode publishedManifest = parse(version.manifestJson());
        path.add(currentAgentId);
        for (JsonNode collaborator : publishedManifest.path("capabilities").path("collaborators")) {
            String nextAgentId = collaborator.path("agentId").asText("").trim();
            if (nextAgentId.isBlank()) continue;
            List<String> cycle = findCollaboratorCycle(
                    rootAgentId, nextAgentId, tenantId, userId, active, visited, path);
            if (!cycle.isEmpty()) return cycle;
        }
        path.remove(path.size() - 1);
        active.remove(currentAgentId);
        return List.of();
    }

    private String tagsJson(JsonNode tags) {
        try {
            return objectMapper.writeValueAsString(tags.isArray() ? tags : objectMapper.createArrayNode());
        } catch (Exception ex) {
            throw new IllegalArgumentException("Unable to serialize Agent tags.", ex);
        }
    }

    private List<String> parseTags(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<List<String>>() { });
        } catch (Exception ex) {
            throw new IllegalStateException("Stored Agent tags are unreadable.", ex);
        }
    }

    private JsonNode parse(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception ex) {
            throw new IllegalStateException("Stored Agent manifest is unreadable.", ex);
        }
    }

    private JsonNode manifestNode(Map<String, Object> manifest) {
        return objectMapper.valueToTree(manifest);
    }

    private Map<String, Object> parseManifestMap(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<Map<String, Object>>() { });
        } catch (Exception ex) {
            throw new IllegalStateException("Stored Agent manifest is unreadable.", ex);
        }
    }

    private static String normalizeVisibility(String visibility) {
        String normalized = visibility == null || visibility.isBlank()
                ? "PRIVATE"
                : visibility.toUpperCase(Locale.ROOT);
        if (!List.of("PRIVATE", "TEAM", "TENANT").contains(normalized)) {
            throw new IllegalArgumentException("Unsupported Agent visibility: " + visibility);
        }
        return normalized;
    }

    private static String normalizeStatus(String status) {
        String normalized = status == null ? "" : status.toUpperCase(Locale.ROOT);
        if (!List.of("ACTIVE", "DISABLED").contains(normalized)) {
            throw new IllegalArgumentException("Unsupported Agent status: " + status);
        }
        return normalized;
    }
}
