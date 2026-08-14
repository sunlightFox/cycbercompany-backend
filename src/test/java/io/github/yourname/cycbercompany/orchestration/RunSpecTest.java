package io.github.yourname.cycbercompany.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.yourname.cycbercompany.tool.AgentApprovalPolicy;
import io.github.yourname.cycbercompany.tool.RiskLevel;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RunSpecTest {

    @Test
    void treatsNullEntriesInPersistedCollectionsAsOmitted() throws Exception {
        List<String> knowledgeBaseIds = new ArrayList<>();
        knowledgeBaseIds.add("kb-1");
        knowledgeBaseIds.add(null);
        List<String> requestedToolNames = new ArrayList<>();
        requestedToolNames.add(null);
        requestedToolNames.add("system.shell.run");
        Set<String> roles = new LinkedHashSet<>();
        roles.add("LOCAL_USER");
        roles.add(null);
        Set<String> scopes = new LinkedHashSet<>();
        scopes.add(null);
        scopes.add("agent:run");

        RunSpec spec = new RunSpec(
                RunSpec.CURRENT_VERSION,
                "conversation-1",
                "check tools",
                "model-1",
                "model-rev-1",
                "agent-1",
                "agent-version-1",
                "sha256:manifest",
                "system prompt",
                "sha256:agent",
                "*",
                "{}",
                List.of(),
                List.of(),
                "sha256:skills",
                "sha256:skill-instructions",
                List.of(),
                null,
                knowledgeBaseIds,
                List.of(),
                requestedToolNames,
                List.of(),
                "node-1",
                RunExecutionMode.NODE_INTERACTION,
                null,
                List.of(),
                null,
                "capability-rev-1",
                "policy-rev-1",
                "on-request",
                "tenant",
                "user",
                roles,
                scopes,
                List.of(),
                "",
                "{}");

        assertThat(spec.knowledgeBaseIds()).containsExactly("kb-1");
        assertThat(spec.requestedToolNames()).containsExactly("system.shell.run");
        assertThat(spec.actor().roles()).containsExactly("LOCAL_USER");
        assertThat(spec.actor().scopes()).containsExactly("agent:run");

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode persistedV1 = mapper.valueToTree(spec);
        persistedV1.put("version", 1);
        persistedV1.remove("agentVersionId");
        persistedV1.remove("agentManifestDigest");
        persistedV1.remove("agentMemoryPolicySnapshot");
        RunSpec restoredV1 = mapper.treeToValue(persistedV1, RunSpec.class);

        assertThat(RunSpec.supports(restoredV1.version())).isTrue();
        assertThat(restoredV1.agentVersionId()).isEmpty();
        assertThat(restoredV1.agentManifestDigest()).isEmpty();
        assertThat(restoredV1.agentMemoryPolicySnapshot()).isEqualTo("{}");
        assertThat(restoredV1.agentApprovalPolicySnapshot()).isEqualTo(AgentApprovalPolicy.sessionOnly());

        AgentApprovalPolicy customPolicy = new AgentApprovalPolicy("CUSTOM", List.of(
                new AgentApprovalPolicy.Rule(RiskLevel.HIGH, AgentApprovalPolicy.Decision.DENY)));
        ObjectNode persistedV4 = mapper.valueToTree(spec);
        persistedV4.set("agentApprovalPolicySnapshot", mapper.valueToTree(customPolicy));

        RunSpec restoredV4 = mapper.treeToValue(persistedV4, RunSpec.class);

        assertThat(restoredV4.agentApprovalPolicySnapshot()).isEqualTo(customPolicy);
    }
}
