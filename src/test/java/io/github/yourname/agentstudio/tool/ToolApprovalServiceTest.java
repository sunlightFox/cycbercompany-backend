package io.github.yourname.agentstudio.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yourname.agentstudio.security.ActorContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ToolApprovalServiceTest {

    @Test
    void mcpCallIsPersistedBeforeExecutionAndApprovalUsesTheExactCapturedBinding() {
        ToolApprovalRepository repository = mock(ToolApprovalRepository.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ToolApprovalService approvalService = new ToolApprovalService(repository, mapper);
        RecordingMcpProvider provider = new RecordingMcpProvider();
        ToolRouter router = new ToolRouter(List.of(provider), approvalService);
        ActorContext actor = new ActorContext(
                "tenant", "local-user", Set.of("LOCAL_USER"), Set.of("agent:run"));
        AtomicReference<ToolApprovalEntity> stored = new AtomicReference<>();
        when(repository.findByTenantIdAndRunIdAndToolCallId("tenant", "run-1", "call-1"))
                .thenReturn(Optional.empty());
        when(repository.save(any(ToolApprovalEntity.class))).thenAnswer(invocation -> {
            ToolApprovalEntity entity = invocation.getArgument(0);
            stored.set(entity);
            return entity;
        });
        when(repository.findByIdAndTenantId(any(), org.mockito.ArgumentMatchers.eq("tenant")))
                .thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        when(repository.findById(any())).thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        ResolvedToolBinding binding = new ResolvedToolBinding(
                "mcp:docs:search", "tool_search", "search", "mcp", "search", "Search docs",
                RiskLevel.HIGH, true, Map.of("type", "object"), Map.of("connectionId", "docs"));
        ToolInvocationRequest request = new ToolInvocationRequest(
                "run-1", "call-1", binding,
                Map.of("connectionId", "attacker", "query", "routing"),
                30, CodingWorkspaceScope.from("workspace"), actor);

        ToolProviderResult pending = router.invoke(request);

        assertThat(pending.requiresApproval()).isTrue();
        assertThat(stored.get().argumentsDigest()).startsWith("sha256:");
        assertThat(stored.get().bindingId()).isEqualTo("mcp:docs:search");
        assertThat(provider.lastInvocation).isNull();

        ToolApprovalDecisionView decision = router.decideApproval(
                pending.approvalId(), new DecideToolApprovalCommand(true), actor);

        assertThat(decision.execution().succeeded()).isTrue();
        assertThat(provider.lastInvocation.binding().attributes()).containsEntry("connectionId", "docs");
        assertThat(provider.lastInvocation.arguments()).containsEntry("connectionId", "attacker");
        assertThat(provider.lastInvocation.approvalId()).isEqualTo(pending.approvalId());
        assertThat(decision.approval().status()).isEqualTo(ToolApprovalStatus.SUCCEEDED);
    }

    @Test
    void rejectionNeverInvokesTheProvider() {
        ToolApprovalRepository repository = mock(ToolApprovalRepository.class);
        ToolApprovalService service = new ToolApprovalService(repository, new ObjectMapper());
        RecordingMcpProvider provider = new RecordingMcpProvider();
        ToolRouter router = new ToolRouter(List.of(provider), service);
        ActorContext actor = new ActorContext("tenant", "local-user", Set.of("LOCAL_USER"), Set.of());
        AtomicReference<ToolApprovalEntity> stored = new AtomicReference<>();
        when(repository.findByTenantIdAndRunIdAndToolCallId(any(), any(), any())).thenReturn(Optional.empty());
        when(repository.save(any(ToolApprovalEntity.class))).thenAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return invocation.getArgument(0);
        });
        when(repository.findByIdAndTenantId(any(), any())).thenAnswer(invocation -> Optional.of(stored.get()));
        ResolvedToolBinding binding = new ResolvedToolBinding(
                "mcp:docs:delete", "tool_delete", "delete", "mcp", "delete", "Delete",
                RiskLevel.HIGH, true, Map.of(), Map.of("connectionId", "docs"));
        ToolProviderResult pending = router.invoke(new ToolInvocationRequest(
                "run-2", "call-2", binding, Map.of(), null, CodingWorkspaceScope.from(null), actor));

        ToolApprovalDecisionView decision = router.decideApproval(
                pending.approvalId(), new DecideToolApprovalCommand(false), actor);

        assertThat(decision.execution()).isNull();
        assertThat(decision.approval().status()).isEqualTo(ToolApprovalStatus.REJECTED);
        assertThat(provider.lastInvocation).isNull();
    }

    private static final class RecordingMcpProvider implements ToolProvider {
        private ToolInvocationRequest lastInvocation;

        @Override public String providerId() { return "mcp"; }
        @Override public List<ToolDescriptor> discover(ToolDiscoveryRequest request) { return List.of(); }
        @Override public ToolProviderResult invoke(ToolInvocationRequest request) {
            lastInvocation = request;
            return new ToolProviderResult("SUCCEEDED", true, Map.of("ok", true), "", null);
        }
    }
}
