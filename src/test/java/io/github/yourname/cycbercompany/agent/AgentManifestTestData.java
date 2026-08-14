package io.github.yourname.cycbercompany.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

final class AgentManifestTestData {

    private AgentManifestTestData() {
    }

    static ObjectNode valid(ObjectMapper mapper) {
        ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", 2);
        ObjectNode identity = root.putObject("identity");
        identity.put("displayName", "Review Partner");
        identity.put("description", "Reviews code changes.");
        identity.putArray("tags").add("review");

        ObjectNode persona = root.putObject("persona");
        persona.put("role", "Senior code reviewer");
        persona.put("mission", "Find material defects before code is merged.");
        persona.putArray("responsibilities").add("Identify correctness and security regressions.");
        persona.putArray("boundaries").add("Do not modify code during a review-only request.");
        persona.putObject("communication")
                .put("defaultLanguage", "en")
                .put("customInstructions", "Lead with findings ordered by severity.");

        ObjectNode capabilities = root.putObject("capabilities");
        capabilities.putObject("model").put("defaultProfileId", "model-review");
        capabilities.putArray("tools").addObject().put("id", "git.diff").put("required", true);
        capabilities.putArray("skills");
        capabilities.putArray("mcpConnections");
        capabilities.putArray("knowledgeBases");
        capabilities.putArray("collaborators");

        ObjectNode memory = root.putObject("memory");
        memory.put("mode", "CONVERSATION");
        memory.putObject("shortTerm").put("strategy", "HYBRID").put("maxContextTokens", 16000);
        ObjectNode longTerm = memory.putObject("longTerm");
        longTerm.put("enabled", false);
        longTerm.putArray("categories");
        longTerm.put("writeMode", "EXPLICIT_ONLY");
        longTerm.put("retrievalMode", "HYBRID");
        longTerm.put("topK", 3);
        longTerm.put("sensitiveDataPolicy", "REJECT");

        root.putObject("runtime")
                .put("autonomy", "ASSIST")
                .put("planning", "IMPLICIT")
                .put("maxSteps", 40)
                .put("timeoutSeconds", 1800);
        ObjectNode safety = root.putObject("safety");
        safety.put("approvalPreset", "CONSERVATIVE");
        safety.putArray("inputGuardrails");
        safety.putArray("outputGuardrails");
        return root;
    }
}
