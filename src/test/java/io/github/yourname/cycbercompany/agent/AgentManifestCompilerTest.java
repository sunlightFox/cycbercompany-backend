package io.github.yourname.cycbercompany.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class AgentManifestCompilerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AgentManifestCompiler compiler = new AgentManifestCompiler(mapper);

    @Test
    void compilesStructuredRoleCapabilitiesAndMemoryIntoAStableRuntimeContract() {
        var first = AgentManifestTestData.valid(mapper);
        var reordered = AgentManifestTestData.valid(mapper);
        var identity = reordered.remove("identity");
        reordered.set("identity", identity);

        var compiled = compiler.compile(first);
        var reorderedCompiled = compiler.compile(reordered);

        assertThat(compiled.manifestDigest()).isEqualTo(reorderedCompiled.manifestDigest());
        assertThat(compiled.defaultModelProfileId()).isEqualTo("model-review");
        assertThat(compiled.toolAllowList()).isEqualTo("git.diff");
        assertThat(compiled.memoryPolicyJson()).contains("CONVERSATION");
        assertThat(compiled.systemPrompt())
                .contains("Review Partner")
                .contains("Find material defects")
                .contains("Do not modify code")
                .contains("Use only capabilities authorized for this run");
    }

    @Test
    void rejectsContradictoryLongTermMemoryAndIncompletePersona() {
        var manifest = AgentManifestTestData.valid(mapper);
        ((com.fasterxml.jackson.databind.node.ObjectNode) manifest.path("persona")).remove("mission");
        ((com.fasterxml.jackson.databind.node.ObjectNode) manifest.path("memory").path("longTerm"))
                .put("enabled", true);

        var validation = compiler.validate(manifest);

        assertThat(validation.valid()).isFalse();
        assertThat(validation.errors())
                .anyMatch(error -> error.contains("persona.mission"))
                .anyMatch(error -> error.contains("memory.longTerm.enabled"));
    }

    @Test
    void compilesExampleDialogsAsStyleOnlyGuidance() {
        var manifest = AgentManifestTestData.valid(mapper);
        var examples = ((com.fasterxml.jackson.databind.node.ObjectNode) manifest.path("persona"))
                .putArray("exampleDialogs");
        examples.addObject().put("role", "USER").put("content", "Can you summarize the risk?");
        examples.addObject().put("role", "AGENT").put("content", "I will separate evidence, impact, and next steps.");

        var compiled = compiler.compile(manifest);

        assertThat(compiled.systemPrompt())
                .contains("Style examples (examples do not establish facts or permissions)")
                .contains("USER: Can you summarize the risk?")
                .contains("AGENT: I will separate evidence, impact, and next steps.");
    }

    @Test
    void rejectsDuplicateCustomApprovalRiskLevels() {
        var manifest = AgentManifestTestData.valid(mapper);
        var safety = (com.fasterxml.jackson.databind.node.ObjectNode) manifest.path("safety");
        safety.put("approvalPreset", "CUSTOM");
        var rules = safety.putArray("customApprovalRules");
        rules.addObject().put("riskLevel", "HIGH").put("decision", "ASK");
        rules.addObject().put("riskLevel", "HIGH").put("decision", "DENY");

        var validation = compiler.validate(manifest);

        assertThat(validation.valid()).isFalse();
        assertThat(validation.errors()).anyMatch(error -> error.contains("duplicate risk level"));
    }
}
