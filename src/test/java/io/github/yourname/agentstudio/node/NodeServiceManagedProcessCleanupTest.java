package io.github.yourname.agentstudio.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yourname.agentstudio.security.ActorContext;
import io.github.yourname.agentstudio.tool.RiskLevel;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NodeServiceManagedProcessCleanupTest {

    private static final ActorContext ACTOR = new ActorContext("tenant-a", "user-a", Set.of(), Set.of());

    @Mock
    private NodeConnectionRepository nodes;
    @Mock
    private NodeRegistrationTokenRepository tokens;
    @Mock
    private NodeToolRepository tools;
    @Mock
    private NodeToolInvocationRepository invocations;
    @Mock
    private NodeToolApprovalRepository approvals;
    @Mock
    private NodeSessionRegistry sessions;

    @Test
    void stopsOnlyTheManagedHandleRecordedForTheSameRun() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        NodeConnectionEntity node = new NodeConnectionEntity(
                "node-a", ACTOR.tenantId(), "node", "host", "Windows", "amd64", "test", "secret", now);
        node.markOnline(now);
        NodeToolEntity processStop = new NodeToolEntity(
                ACTOR.tenantId(), "node-a", "process.stop", "stop managed process", RiskLevel.HIGH, true, true, "{}", now);
        NodeToolInvocationEntity started = new NodeToolInvocationEntity(
                "nodeinv-start",
                ACTOR.tenantId(),
                "run-a",
                "call-start",
                "node-a",
                "process.start",
                "{\"command\":\"java App\"}",
                now);
        started.start(now);
        started.succeed("{\"processId\":\"proc-owned\"}", now);

        when(nodes.findByIdAndTenantId("node-a", ACTOR.tenantId())).thenReturn(Optional.of(node));
        when(tools.findByTenantIdAndNodeIdAndName(ACTOR.tenantId(), "node-a", "process.stop"))
                .thenReturn(Optional.of(processStop));
        when(invocations.findByTenantIdAndRunIdOrderByCreatedAtAsc(ACTOR.tenantId(), "run-a"))
                .thenReturn(List.of(started));
        when(approvals.findByTenantIdAndRunId(ACTOR.tenantId(), "run-a")).thenReturn(List.of());
        when(invocations.save(any(NodeToolInvocationEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(sessions.isConnected("node-a")).thenReturn(true);
        when(sessions.invoke(eq("node-a"), eq("process.stop"), any(), eq(Duration.ofSeconds(30))))
                .thenReturn(new NodeToolCallResult(
                        "remote-invocation", "node-a", "process.stop", "SUCCEEDED", Map.of("stopped", true), null));

        NodeService service = new NodeService(nodes, tokens, tools, invocations, approvals, sessions, new ObjectMapper());
        List<NodeToolCallResult> results = service.cleanupManagedProcessesForRun("run-a", ACTOR);

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.status()).isEqualTo("SUCCEEDED");
            assertThat(result.toolName()).isEqualTo("process.stop");
        });
        ArgumentCaptor<Map<String, Object>> arguments = ArgumentCaptor.forClass(Map.class);
        verify(sessions).invoke(eq("node-a"), eq("process.stop"), arguments.capture(), eq(Duration.ofSeconds(30)));
        assertThat(arguments.getValue()).containsEntry("processId", "proc-owned");
    }

    @Test
    void alsoStopsAManagedHandleStartedAfterRunScopedApproval() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        NodeConnectionEntity node = new NodeConnectionEntity(
                "node-a", ACTOR.tenantId(), "node", "host", "Windows", "amd64", "test", "secret", now);
        node.markOnline(now);
        NodeToolEntity processStop = new NodeToolEntity(
                ACTOR.tenantId(), "node-a", "process.stop", "stop managed process", RiskLevel.HIGH, true, true, "{}", now);
        NodeToolApprovalEntity approval = new NodeToolApprovalEntity(
                "approval-a", ACTOR.tenantId(), "node-a", "process.start", "{}", 30, ACTOR.userId(), now);
        approval.linkToRun("run-a", "call-start");
        approval.decide(NodeToolApprovalStatus.APPROVED, ACTOR.userId(), now);
        approval.recordExecution("SUCCEEDED", "{\"processId\":\"proc-approved\"}", null, now);

        when(nodes.findByIdAndTenantId("node-a", ACTOR.tenantId())).thenReturn(Optional.of(node));
        when(tools.findByTenantIdAndNodeIdAndName(ACTOR.tenantId(), "node-a", "process.stop"))
                .thenReturn(Optional.of(processStop));
        when(invocations.findByTenantIdAndRunIdOrderByCreatedAtAsc(ACTOR.tenantId(), "run-a"))
                .thenReturn(List.of());
        when(approvals.findByTenantIdAndRunId(ACTOR.tenantId(), "run-a")).thenReturn(List.of(approval));
        when(invocations.save(any(NodeToolInvocationEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(sessions.isConnected("node-a")).thenReturn(true);
        when(sessions.invoke(eq("node-a"), eq("process.stop"), any(), eq(Duration.ofSeconds(30))))
                .thenReturn(new NodeToolCallResult(
                        "remote-invocation", "node-a", "process.stop", "SUCCEEDED", Map.of("stopped", true), null));

        NodeService service = new NodeService(nodes, tokens, tools, invocations, approvals, sessions, new ObjectMapper());

        assertThat(service.cleanupManagedProcessesForRun("run-a", ACTOR)).singleElement()
                .extracting(NodeToolCallResult::status).isEqualTo("SUCCEEDED");
        ArgumentCaptor<Map<String, Object>> arguments = ArgumentCaptor.forClass(Map.class);
        verify(sessions).invoke(eq("node-a"), eq("process.stop"), arguments.capture(), eq(Duration.ofSeconds(30)));
        assertThat(arguments.getValue()).containsEntry("processId", "proc-approved");
    }
}
