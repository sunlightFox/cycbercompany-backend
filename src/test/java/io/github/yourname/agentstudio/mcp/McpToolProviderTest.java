package io.github.yourname.agentstudio.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yourname.agentstudio.security.ActorContext;
import io.github.yourname.agentstudio.tool.CodingWorkspaceScope;
import io.github.yourname.agentstudio.tool.ResolvedToolBinding;
import io.github.yourname.agentstudio.tool.RiskLevel;
import io.github.yourname.agentstudio.tool.ToolDiscoveryRequest;
import io.github.yourname.agentstudio.tool.ToolInvocationRequest;
import io.github.yourname.agentstudio.tool.ToolRouter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class McpToolProviderTest {

    @Test
    void invokesOnlyTheConnectionCapturedDuringDiscovery() {
        McpConnectionService service = mock(McpConnectionService.class);
        McpToolProvider provider = new McpToolProvider(service, new ObjectMapper());
        ActorContext actor = new ActorContext("tenant", "user", Set.of(), Set.of());
        McpToolView tool = new McpToolView(
                "tool-1", "search", "Search docs", "{\"type\":\"object\"}",
                RiskLevel.LOW, false, true, Instant.now());
        when(service.getConnection("docs")).thenReturn(new McpConnectionView(
                "docs", "Docs", "", McpTransportType.STDIO, true, McpConnectionStatus.CONFIGURED,
                "server", List.of(), null, List.of(), Map.of(), List.of(tool), Instant.now(), Instant.now()));
        when(service.callTool(eq("docs"), eq("search"), org.mockito.ArgumentMatchers.any(), eq("run-1"), eq(actor)))
                .thenReturn(new McpToolCallResult("docs", "search", false, "found", List.of(), null));
        ResolvedToolBinding binding = new ToolRouter(List.of(provider)).resolve(
                new ToolDiscoveryRequest("run-1", null, List.of("docs"), actor), List.of("*"), "mcp:*").getFirst();

        var result = provider.invoke(new ToolInvocationRequest(
                "run-1", "call-1", binding,
                Map.of("connectionId", "attacker", "query", "routing"),
                null, CodingWorkspaceScope.from(null), actor));

        verify(service).callTool(eq("docs"), eq("search"), org.mockito.ArgumentMatchers.any(), eq("run-1"), eq(actor));
        assertThat(result.succeeded()).isTrue();
        assertThat(result.result()).containsEntry("connectionId", "docs");
    }
}
