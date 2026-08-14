package io.github.yourname.cycbercompany.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yourname.cycbercompany.security.ActorContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NodeServiceRunCancellationTest {

    private static final ActorContext ACTOR =
            new ActorContext("tenant-cancel", "user-cancel", Set.of(), Set.of());

    @Test
    void cancellationExpiresPendingApprovalAndSendsCancelToActiveInvocation() {
        NodeConnectionRepository nodes = mock(NodeConnectionRepository.class);
        NodeRegistrationTokenRepository tokens = mock(NodeRegistrationTokenRepository.class);
        NodeToolRepository tools = mock(NodeToolRepository.class);
        NodeToolInvocationRepository invocations = mock(NodeToolInvocationRepository.class);
        NodeToolApprovalRepository approvals = mock(NodeToolApprovalRepository.class);
        NodeSessionRegistry sessions = mock(NodeSessionRegistry.class);
        Instant now = Instant.now();

        NodeToolInvocationEntity invocation = new NodeToolInvocationEntity(
                "inv-active", ACTOR.tenantId(), "run-cancel", "call-1", "node-1", "shell.run", "{}", now);
        invocation.start(now);
        NodeToolApprovalEntity approval = new NodeToolApprovalEntity(
                "approval-pending", ACTOR.tenantId(), "node-1", "shell.run", "{}", 30, ACTOR.userId(), now);
        approval.linkToRun("run-cancel", "call-2");

        when(invocations.findByTenantIdAndRunIdOrderByCreatedAtAsc(ACTOR.tenantId(), "run-cancel"))
                .thenReturn(List.of(invocation));
        when(approvals.findByTenantIdAndRunId(ACTOR.tenantId(), "run-cancel"))
                .thenReturn(List.of(approval));
        when(sessions.cancel("node-1", "inv-active", "trace_inv-active")).thenReturn(true);

        NodeService service = new NodeService(nodes, tokens, tools, invocations, approvals, sessions, new ObjectMapper());

        assertThat(service.cancelRunInvocations("run-cancel", ACTOR)).isEqualTo(1);
        assertThat(approval.status()).isEqualTo(NodeToolApprovalStatus.EXPIRED);
        verify(approvals).save(approval);
        verify(sessions).cancel("node-1", "inv-active", "trace_inv-active");
    }

    @Test
    void completedInvocationIsNotCancelledAgain() {
        NodeConnectionRepository nodes = mock(NodeConnectionRepository.class);
        NodeRegistrationTokenRepository tokens = mock(NodeRegistrationTokenRepository.class);
        NodeToolRepository tools = mock(NodeToolRepository.class);
        NodeToolInvocationRepository invocations = mock(NodeToolInvocationRepository.class);
        NodeToolApprovalRepository approvals = mock(NodeToolApprovalRepository.class);
        NodeSessionRegistry sessions = mock(NodeSessionRegistry.class);
        Instant now = Instant.now();
        NodeToolInvocationEntity invocation = new NodeToolInvocationEntity(
                "inv-done", ACTOR.tenantId(), "run-cancel", "call-1", "node-1", "shell.run", "{}", now);
        invocation.succeed("{}", now);

        when(invocations.findByTenantIdAndRunIdOrderByCreatedAtAsc(ACTOR.tenantId(), "run-cancel"))
                .thenReturn(List.of(invocation));
        when(approvals.findByTenantIdAndRunId(ACTOR.tenantId(), "run-cancel")).thenReturn(List.of());

        NodeService service = new NodeService(nodes, tokens, tools, invocations, approvals, sessions, new ObjectMapper());

        assertThat(service.cancelRunInvocations("run-cancel", ACTOR)).isZero();
    }
}
