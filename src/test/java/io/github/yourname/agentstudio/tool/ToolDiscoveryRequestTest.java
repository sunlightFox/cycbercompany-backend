package io.github.yourname.agentstudio.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yourname.agentstudio.security.ActorContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ToolDiscoveryRequestTest {

    @Test
    void treatsNullListEntriesAsOmitted() {
        ActorContext actor = new ActorContext("tenant", "user", Set.of(), Set.of());
        List<String> knowledgeBaseIds = new ArrayList<>();
        knowledgeBaseIds.add("kb-1");
        knowledgeBaseIds.add(null);
        List<String> mcpConnectionIds = new ArrayList<>();
        mcpConnectionIds.add(null);
        mcpConnectionIds.add("mcp-1");

        ToolDiscoveryRequest request = new ToolDiscoveryRequest(
                "run-1",
                "node-1",
                knowledgeBaseIds,
                mcpConnectionIds,
                new ArrayList<>(),
                actor);

        assertThat(request.knowledgeBaseIds()).containsExactly("kb-1");
        assertThat(request.mcpConnectionIds()).containsExactly("mcp-1");
        assertThat(request.skillBindings()).isEmpty();
    }
}
