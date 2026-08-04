package io.github.yourname.agentstudio.skill;

import io.github.yourname.agentstudio.tool.RiskLevel;
import io.github.yourname.agentstudio.tool.ToolDescriptor;
import io.github.yourname.agentstudio.tool.ToolDiscoveryRequest;
import io.github.yourname.agentstudio.tool.ToolInvocationRequest;
import io.github.yourname.agentstudio.tool.ToolProvider;
import io.github.yourname.agentstudio.tool.ToolProviderResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Lets an Agent prepare a disabled local Skill after the host applies the Run approval policy. */
@Service
public class SkillAuthoringToolProvider implements ToolProvider {

    public static final String PROVIDER_ID = "skill-authoring";

    private final SkillCatalog catalog;

    public SkillAuthoringToolProvider(SkillCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public List<ToolDescriptor> discover(ToolDiscoveryRequest request) {
        return List.of(new ToolDescriptor(
                "skill-authoring:create-draft",
                "skill.create_draft",
                PROVIDER_ID,
                "create-draft",
                "Create a disabled local Skill draft from supplied SKILL.md content. Use only when the user explicitly "
                        + "asks to create a Skill. The host applies the current Run approval mode to the exact ID and "
                        + "content; report a saved draft only after a SUCCEEDED result.",
                RiskLevel.MEDIUM,
                true,
                objectSchema(Map.of(
                        "id", Map.of("type", "string", "minLength", 1, "maxLength", 120,
                                "description", "Stable local Skill ID using lowercase letters, digits, dots, underscores, or hyphens."),
                        "skillMarkdown", Map.of("type", "string", "minLength", 1,
                                "description", "Complete reviewed SKILL.md content, including optional YAML frontmatter.")),
                        "id", "skillMarkdown"),
                Map.of()));
    }

    @Override
    public ToolProviderResult invoke(ToolInvocationRequest request) {
        if (!PROVIDER_ID.equals(request.binding().providerId())
                || !"create-draft".equals(request.binding().providerToolName())) {
            throw new IllegalArgumentException("SkillAuthoringToolProvider cannot invoke binding: " + request.binding().bindingId());
        }
        try {
            String id = required(request.arguments(), "id");
            String markdown = required(request.arguments(), "skillMarkdown");
            SkillView created = catalog.create(new CreateSkillCommand(id, markdown, false, false));
            return new ToolProviderResult(
                    "SUCCEEDED",
                    true,
                    Map.of("skill", created, "message", "Skill draft created and left disabled for review."),
                    "",
                    null);
        } catch (Exception ex) {
            return new ToolProviderResult("FAILED", false, Map.of(), message(ex), null);
        }
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties, String... required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of(required));
        schema.put("additionalProperties", false);
        return Map.copyOf(schema);
    }

    private static String required(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("Skill authoring argument '" + name + "' must be a non-empty string.");
        }
        return text.trim();
    }

    private static String message(Exception ex) {
        return ex.getMessage() == null || ex.getMessage().isBlank()
                ? ex.getClass().getSimpleName()
                : ex.getMessage();
    }
}
