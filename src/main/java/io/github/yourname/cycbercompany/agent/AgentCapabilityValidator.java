package io.github.yourname.cycbercompany.agent;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.yourname.cycbercompany.knowledge.KnowledgeBaseRepository;
import io.github.yourname.cycbercompany.mcp.McpConnectionService;
import io.github.yourname.cycbercompany.mcp.McpConnectionView;
import io.github.yourname.cycbercompany.skill.SkillCatalog;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Checks that an Agent's persisted references can be resolved by the current runtime. */
@Service
public class AgentCapabilityValidator {

    private final SkillCatalog skills;
    private final McpConnectionService mcpConnections;
    private final KnowledgeBaseRepository knowledgeBases;

    public AgentCapabilityValidator(
            SkillCatalog skills,
            McpConnectionService mcpConnections,
            KnowledgeBaseRepository knowledgeBases) {
        this.skills = skills;
        this.mcpConnections = mcpConnections;
        this.knowledgeBases = knowledgeBases;
    }

    public List<String> validate(JsonNode manifest, String tenantId, String userId) {
        JsonNode capabilities = manifest == null ? null : manifest.path("capabilities");
        if (capabilities == null || !capabilities.isObject()) {
            return List.of("capabilities must be an object before references can be resolved.");
        }
        List<String> errors = new ArrayList<>();
        List<String> skillIds = referenceIds(capabilities, "skills");
        if (!skillIds.isEmpty()) {
            try {
                skills.resolveForRun(skillIds);
            } catch (RuntimeException ex) {
                errors.add("Skill references are not available: " + message(ex));
            }
        }

        List<String> knowledgeIds = referenceIds(capabilities, "knowledgeBases");
        for (String id : knowledgeIds) {
            if (knowledgeBases.findByIdAndTenantId(id, tenantId).isEmpty()) {
                errors.add("Knowledge base is not available: " + id);
            }
        }

        List<String> mcpIds = referenceIds(capabilities, "mcpConnections");
        Set<String> enabledMcpIds = mcpConnections.listConnections().stream()
                .filter(McpConnectionView::enabled)
                .map(McpConnectionView::id)
                .collect(java.util.stream.Collectors.toSet());
        for (String id : mcpIds) {
            if (!enabledMcpIds.contains(id)) {
                errors.add("MCP connection is not available: " + id);
            }
        }

        return List.copyOf(new java.util.LinkedHashSet<>(errors));
    }

    private static List<String> referenceIds(JsonNode parent, String field) {
        Set<String> ids = new java.util.LinkedHashSet<>();
        JsonNode values = parent.path(field);
        if (!values.isArray()) return List.of();
        for (JsonNode value : values) {
            String id = value.path("id").asText("").trim();
            if (!id.isBlank()) ids.add(id);
        }
        return List.copyOf(ids);
    }

    private static String message(RuntimeException ex) {
        return ex.getMessage() == null || ex.getMessage().isBlank() ? ex.getClass().getSimpleName() : ex.getMessage();
    }
}
