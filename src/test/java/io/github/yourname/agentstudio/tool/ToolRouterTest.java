package io.github.yourname.agentstudio.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.yourname.agentstudio.security.ActorContext;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ToolRouterTest {

    private static final ActorContext ACTOR = new ActorContext("tenant", "user", Set.of(), Set.of());
    private static final ToolDiscoveryRequest DISCOVERY =
            new ToolDiscoveryRequest("run-1", "node-1", List.of("search"), ACTOR);

    @Test
    void rejectsDuplicateProviderIds() {
        assertThatThrownBy(() -> new ToolRouter(List.of(
                        provider("node", descriptor("node", "node:1:fs.read", "fs.read")),
                        provider("node", descriptor("node", "node:2:fs.read", "fs.read")))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate ToolProvider ID");
    }

    @Test
    void rejectsDuplicateBindingIdsAcrossProviders() {
        ToolRouter router = new ToolRouter(List.of(
                provider("node", descriptor("node", "shared-binding", "fs.read")),
                provider("mcp", descriptor("mcp", "shared-binding", "fs.read"))));

        assertThatThrownBy(() -> router.resolve(DISCOVERY, List.of("*"), "*"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate tool binding ID");
    }

    @Test
    void intersectsAgentAllowListWithRunSelection() {
        ToolRouter router = new ToolRouter(List.of(provider(
                "node",
                descriptor("node", "node:1:fs.read", "fs.read"),
                descriptor("node", "node:1:fs.write", "fs.write"),
                descriptor("node", "node:1:shell.run", "shell.run"))));

        List<ResolvedToolBinding> bindings = router.resolve(DISCOVERY, List.of("fs.*"), "fs.read,shell.run");

        assertThat(bindings).extracting(ResolvedToolBinding::logicalName).containsExactly("fs.read");
    }

    @Test
    void givesSameLogicalToolDifferentStableModelNames() {
        ToolRouter router = new ToolRouter(List.of(
                provider("node", descriptor("node", "node:1:search", "search")),
                provider("mcp", descriptor("mcp", "mcp:docs:search", "search"))));

        List<ResolvedToolBinding> bindings = router.resolve(DISCOVERY, List.of("*"), "*");

        assertThat(bindings).hasSize(2);
        assertThat(bindings).extracting(ResolvedToolBinding::modelName).doesNotHaveDuplicates();
    }

    @Test
    void invokesTheProviderCapturedInTheBindingAndIgnoresRoutingArguments() {
        RecordingProvider node = new RecordingProvider(
                "node", descriptor("node", "node:trusted:fs.read", "fs.read"));
        RecordingProvider mcp = new RecordingProvider(
                "mcp", descriptor("mcp", "mcp:other:fs.read", "fs.read"));
        ToolRouter router = new ToolRouter(List.of(node, mcp));
        ResolvedToolBinding binding = router.resolve(DISCOVERY, List.of("node:*"), "*").getFirst();

        router.invoke(new ToolInvocationRequest(
                "run-1",
                "call-1",
                binding,
                Map.of("providerId", "mcp", "nodeId", "attacker", "path", "README.md"),
                null,
                CodingWorkspaceScope.from("demo"),
                ACTOR));

        assertThat(node.lastRequest).isNotNull();
        assertThat(node.lastRequest.binding().bindingId()).isEqualTo("node:trusted:fs.read");
        assertThat(mcp.lastRequest).isNull();
    }

    private static ToolDescriptor descriptor(String providerId, String bindingId, String logicalName) {
        return new ToolDescriptor(
                bindingId,
                logicalName,
                providerId,
                logicalName,
                logicalName,
                RiskLevel.LOW,
                false,
                Map.of("type", "object"),
                Map.of());
    }

    private static ToolProvider provider(String providerId, ToolDescriptor... descriptors) {
        return new ToolProvider() {
            @Override public String providerId() { return providerId; }
            @Override public List<ToolDescriptor> discover(ToolDiscoveryRequest request) { return List.of(descriptors); }
            @Override public ToolProviderResult invoke(ToolInvocationRequest request) {
                return new ToolProviderResult("SUCCEEDED", true, Map.of(), "", null);
            }
        };
    }

    private static final class RecordingProvider implements ToolProvider {
        private final String id;
        private final ToolDescriptor descriptor;
        private ToolInvocationRequest lastRequest;

        private RecordingProvider(String id, ToolDescriptor descriptor) {
            this.id = id;
            this.descriptor = descriptor;
        }

        @Override public String providerId() { return id; }
        @Override public List<ToolDescriptor> discover(ToolDiscoveryRequest request) { return List.of(descriptor); }
        @Override public ToolProviderResult invoke(ToolInvocationRequest request) {
            lastRequest = request;
            return new ToolProviderResult("SUCCEEDED", true, Map.of(), "", null);
        }
    }
}
