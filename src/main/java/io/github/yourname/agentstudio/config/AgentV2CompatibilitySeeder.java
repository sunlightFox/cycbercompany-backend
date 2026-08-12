package io.github.yourname.agentstudio.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.yourname.agentstudio.agent.AgentDefinitionEntity;
import io.github.yourname.agentstudio.agent.AgentDefinitionRepository;
import io.github.yourname.agentstudio.agent.AgentIdentityEntity;
import io.github.yourname.agentstudio.agent.AgentIdentityRepository;
import io.github.yourname.agentstudio.agent.AgentManifestCompiler;
import io.github.yourname.agentstudio.agent.AgentVersionEntity;
import io.github.yourname.agentstudio.agent.AgentVersionRepository;
import io.github.yourname.agentstudio.security.SecurityProperties;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Order(100)
class AgentV2CompatibilitySeeder implements ApplicationRunner {

    private final AgentDefinitionRepository legacyAgents;
    private final AgentIdentityRepository identities;
    private final AgentVersionRepository versions;
    private final AgentManifestCompiler compiler;
    private final ObjectMapper objectMapper;
    private final SecurityProperties security;

    AgentV2CompatibilitySeeder(
            AgentDefinitionRepository legacyAgents,
            AgentIdentityRepository identities,
            AgentVersionRepository versions,
            AgentManifestCompiler compiler,
            ObjectMapper objectMapper,
            SecurityProperties security) {
        this.legacyAgents = legacyAgents;
        this.identities = identities;
        this.versions = versions;
        this.compiler = compiler;
        this.objectMapper = objectMapper;
        this.security = security;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String tenantId = security.tokenMode() ? security.tenantId() : "local";
        String userId = security.tokenMode() ? security.userId() : "local-user";
        for (AgentDefinitionEntity legacy : legacyAgents.findAll()) {
            if (identities.findByIdAndTenantId(legacy.id(), tenantId).isPresent()) {
                continue;
            }
            importLegacy(legacy, tenantId, userId);
        }
    }

    private void importLegacy(AgentDefinitionEntity legacy, String tenantId, String userId) {
        Instant now = Instant.now();
        ObjectNode manifest = manifest(legacy);
        var compiled = compiler.compile(manifest);
        AgentIdentityEntity identity = new AgentIdentityEntity(
                legacy.id(),
                tenantId,
                userId,
                legacy.name(),
                legacy.description(),
                "",
                "通用",
                "[]",
                tenantId.equals("local") ? "PUBLIC" : "PRIVATE",
                now);
        AgentVersionEntity version = new AgentVersionEntity(
                UUID.randomUUID().toString(), legacy.id(), tenantId, 1, compiled, userId, now);
        version.preserveLegacyRuntimeSnapshot(
                legacy.systemPrompt(), legacy.defaultModelProfileId(), legacy.toolAllowList());
        version.publish(now);
        identity.publish(version.id(), now);
        identities.save(identity);
        versions.save(version);
    }

    private ObjectNode manifest(AgentDefinitionEntity legacy) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schemaVersion", 2);
        ObjectNode identity = root.putObject("identity");
        identity.put("displayName", legacy.name());
        identity.put("description", legacy.description() == null ? "" : legacy.description());
        identity.putArray("tags");

        ObjectNode persona = root.putObject("persona");
        persona.put("role", legacy.name());
        persona.put("mission", legacy.description() == null || legacy.description().isBlank()
                ? "Assist the user within the configured runtime boundaries."
                : legacy.description());
        persona.putArray("responsibilities").add("Follow the configured instructions and complete authorized tasks.");
        persona.putArray("boundaries").add("Use only capabilities authorized for the current run.");
        persona.putObject("communication").put("customInstructions", legacy.systemPrompt());

        ObjectNode capabilities = root.putObject("capabilities");
        capabilities.putObject("model").put("defaultProfileId", legacy.defaultModelProfileId());
        ArrayNode tools = capabilities.putArray("tools");
        parseTools(legacy.toolAllowList()).forEach(tool -> tools.addObject().put("id", tool).put("required", false));
        capabilities.putArray("skills");
        capabilities.putArray("mcpConnections");
        capabilities.putArray("knowledgeBases");
        capabilities.putArray("collaborators");

        ObjectNode memory = root.putObject("memory");
        memory.put("mode", "CONVERSATION");
        memory.putObject("shortTerm").put("strategy", "HYBRID").put("maxContextTokens", 24000);
        ObjectNode longTerm = memory.putObject("longTerm");
        longTerm.put("enabled", false);
        longTerm.putArray("categories");
        longTerm.put("writeMode", "EXPLICIT_ONLY");
        longTerm.put("retrievalMode", "HYBRID");
        longTerm.put("topK", 3);
        longTerm.put("sensitiveDataPolicy", "REJECT");

        root.putObject("runtime")
                .put("autonomy", "EXECUTE")
                .put("planning", "IMPLICIT")
                .put("maxSteps", 80)
                .put("timeoutSeconds", 3600);
        ObjectNode safety = root.putObject("safety");
        safety.put("approvalPreset", "BALANCED");
        safety.putArray("inputGuardrails");
        safety.putArray("outputGuardrails");
        return root;
    }

    private static java.util.List<String> parseTools(String value) {
        if (value == null || value.isBlank()) {
            return java.util.List.of();
        }
        return Arrays.stream(value.split("[,\\s]+"))
                .map(String::trim)
                .filter(tool -> !tool.isBlank())
                .distinct()
                .toList();
    }
}
