package io.github.yourname.agentstudio.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RunSpecTest {

    @Test
    void treatsNullEntriesInPersistedCollectionsAsOmitted() {
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
                "system prompt",
                "sha256:agent",
                "*",
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
                scopes);

        assertThat(spec.knowledgeBaseIds()).containsExactly("kb-1");
        assertThat(spec.requestedToolNames()).containsExactly("system.shell.run");
        assertThat(spec.actor().roles()).containsExactly("LOCAL_USER");
        assertThat(spec.actor().scopes()).containsExactly("agent:run");
    }
}
