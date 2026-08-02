package io.github.yourname.agentstudio.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yourname.agentstudio.security.ActorContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 验证人工对账只查询 Journal，绝不重新执行历史命令。 */
class NodeServiceRunReconciliationTest {

    private static final ActorContext ACTOR =
            new ActorContext("tenant-reconcile", "user-reconcile", Set.of(), Set.of());

    @Test
    void sendsOnlyToolStatusForUnknownInvocation() throws Exception {
        NodeConnectionRepository nodes = mock(NodeConnectionRepository.class);
        NodeToolInvocationRepository invocations = mock(NodeToolInvocationRepository.class);
        NodeSessionRegistry sessions = mock(NodeSessionRegistry.class);
        Instant now = Instant.now();
        NodeToolInvocationEntity invocation = invocation("inv-unknown", "node-1", now);
        invocation.unknown("network interrupted", now);
        NodeConnectionEntity node = onlineNode("node-1", now);
        when(invocations.findByTenantIdAndRunIdAndStatusInOrderByCreatedAtAsc(
                eq(ACTOR.tenantId()), eq("run-1"), any())).thenReturn(List.of(invocation));
        when(nodes.findByIdAndTenantId("node-1", ACTOR.tenantId())).thenReturn(Optional.of(node));
        when(sessions.isConnected("node-1")).thenReturn(true);

        NodeService service = service(nodes, invocations, sessions);
        RunNodeReconciliationView result = service.requestRunReconciliation("run-1", ACTOR);

        verify(sessions).sendControl(eq("node-1"), eq("tool.status"), eq("inv-unknown"), eq(Map.of(
                "invocationId", "inv-unknown",
                "toolName", "fs.write",
                "argumentsDigest", "sha256:args",
                "attempt", 2)));
        // 关键安全断言：手动对账路径没有调用 invoke，因此不会再次写入文件。
        verify(sessions, never()).invoke(anyString(), any(NodeInvocationDispatch.class), any());
        assertThat(result.statusRequested()).isEqualTo(1);
        assertThat(result.nodeUnavailable()).isZero();
        assertThat(result.invocations()).extracting(RunNodeReconciliationView.Invocation::outcome)
                .containsExactly(RunNodeReconciliationView.Outcome.STATUS_REQUESTED);
    }

    @Test
    void leavesInvocationUntouchedWhenTheNodeIsUnavailable() throws Exception {
        NodeConnectionRepository nodes = mock(NodeConnectionRepository.class);
        NodeToolInvocationRepository invocations = mock(NodeToolInvocationRepository.class);
        NodeSessionRegistry sessions = mock(NodeSessionRegistry.class);
        Instant now = Instant.now();
        NodeToolInvocationEntity invocation = invocation("inv-offline", "node-offline", now);
        when(invocations.findByTenantIdAndRunIdAndStatusInOrderByCreatedAtAsc(
                eq(ACTOR.tenantId()), eq("run-1"), any())).thenReturn(List.of(invocation));
        when(nodes.findByIdAndTenantId("node-offline", ACTOR.tenantId())).thenReturn(Optional.empty());

        RunNodeReconciliationView result = service(nodes, invocations, sessions)
                .requestRunReconciliation("run-1", ACTOR);

        verify(sessions, never()).sendControl(anyString(), anyString(), anyString(), any());
        assertThat(result.statusRequested()).isZero();
        assertThat(result.nodeUnavailable()).isEqualTo(1);
        assertThat(result.invocations().getFirst().outcome())
                .isEqualTo(RunNodeReconciliationView.Outcome.NODE_UNAVAILABLE);
        assertThat(invocation.status()).isEqualTo(NodeToolInvocationStatus.DISPATCHED);
    }

    private static NodeService service(
            NodeConnectionRepository nodes,
            NodeToolInvocationRepository invocations,
            NodeSessionRegistry sessions) {
        return new NodeService(
                nodes,
                mock(NodeRegistrationTokenRepository.class),
                mock(NodeToolRepository.class),
                invocations,
                mock(NodeToolApprovalRepository.class),
                sessions,
                new ObjectMapper());
    }

    private static NodeToolInvocationEntity invocation(String id, String nodeId, Instant now) {
        NodeToolInvocationEntity invocation = new NodeToolInvocationEntity(
                id, ACTOR.tenantId(), "run-1", "call-1", nodeId, "fs.write", "{}", now);
        invocation.dispatch(2, now.plusSeconds(30), "sha256:args", "idem-1", "policy-1", now);
        return invocation;
    }

    private static NodeConnectionEntity onlineNode(String id, Instant now) {
        NodeConnectionEntity node = new NodeConnectionEntity(
                id, ACTOR.tenantId(), id, "host", "Windows", "amd64", "test", "secret", now);
        node.markOnline(now);
        return node;
    }
}
