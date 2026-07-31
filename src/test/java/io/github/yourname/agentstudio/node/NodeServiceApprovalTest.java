package io.github.yourname.agentstudio.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yourname.agentstudio.security.ActorContext;
import io.github.yourname.agentstudio.tool.RiskLevel;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NodeServiceApprovalTest {

    private static final String TENANT = "tenant-a";
    private static final String NODE_ID = "node-1";
    private static final String TOOL_NAME = "shell.run";
    private static final ActorContext ACTOR = new ActorContext(TENANT, "alice", Set.of(), Set.of());

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

    private NodeService service;
    private NodeToolApprovalEntity approval;

    @BeforeEach
    void setUp() {
        service = new NodeService(nodes, tokens, tools, invocations, approvals, sessions, new ObjectMapper());
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        NodeConnectionEntity node = new NodeConnectionEntity(
                NODE_ID, TENANT, "local", "host", "Windows", "amd64", "test", "secret", now);
        node.markOnline(now);
        NodeToolEntity tool = new NodeToolEntity(
                TENANT, NODE_ID, TOOL_NAME, "run a command", RiskLevel.HIGH, true, true, "{}", now);
        approval = new NodeToolApprovalEntity(
                "nodeapproval-1", TENANT, NODE_ID, TOOL_NAME, "{\"command\":\"whoami\"}", 45, "alice", now);

        lenient().when(nodes.findByIdAndTenantId(NODE_ID, TENANT)).thenReturn(Optional.of(node));
        lenient().when(tools.findByTenantIdAndNodeIdAndName(TENANT, NODE_ID, TOOL_NAME)).thenReturn(Optional.of(tool));
        lenient().when(approvals.save(any(NodeToolApprovalEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(approvals.saveAndFlush(any(NodeToolApprovalEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(invocations.save(any(NodeToolInvocationEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void directCallCreatesApprovalWithoutSendingNodeCommand() {
        NodeToolCallResult result = service.callTool(
                NODE_ID, TOOL_NAME, new CallNodeToolCommand(Map.of("command", "whoami"), 45), ACTOR);

        assertEquals("APPROVAL_REQUIRED", result.status());
        assertEquals("PENDING", result.result().get("status"));
        verify(approvals).save(any(NodeToolApprovalEntity.class));
        verifyNoInteractions(sessions);
    }

    @Test
    void codingRunApprovalIsLinkedToItsRunAndModelToolCall() {
        AtomicReference<NodeToolApprovalEntity> createdApproval = new AtomicReference<>();
        when(approvals.save(any(NodeToolApprovalEntity.class))).thenAnswer(invocation -> {
            NodeToolApprovalEntity value = invocation.getArgument(0);
            createdApproval.set(value);
            return value;
        });
        when(approvals.findByIdAndTenantId(any(), eq(TENANT)))
                .thenAnswer(invocation -> Optional.ofNullable(createdApproval.get()));

        NodeToolCallResult result = service.callToolForRun(
                "run-1",
                "model-call-7",
                NODE_ID,
                TOOL_NAME,
                new CallNodeToolCommand(Map.of("command", "whoami"), 45),
                ACTOR);

        assertEquals("APPROVAL_REQUIRED", result.status());
        assertEquals("run-1", createdApproval.get().runId());
        assertEquals("model-call-7", createdApproval.get().toolCallId());
        verifyNoInteractions(sessions);
    }

    @Test
    void rejectionNeverInvokesTheNode() {
        when(approvals.findByIdAndTenantId(approval.id(), TENANT)).thenReturn(Optional.of(approval));

        NodeToolApprovalDecisionView decision = service.decideToolApproval(
                approval.id(), new DecideNodeToolApprovalCommand(false), ACTOR);

        assertEquals(NodeToolApprovalStatus.REJECTED, decision.approval().status());
        assertNull(decision.execution());
        verify(sessions, never()).invoke(any(), any(), any(), any());
    }

    @Test
    void approvalExecutesStoredArgumentsOnlyOnce() {
        when(approvals.findByIdAndTenantId(approval.id(), TENANT)).thenReturn(Optional.of(approval));
        when(sessions.isConnected(NODE_ID)).thenReturn(true);
        when(sessions.invoke(eq(NODE_ID), eq(TOOL_NAME), any(), eq(Duration.ofSeconds(45))))
                .thenReturn(new NodeToolCallResult(
                        "nodeinv-1", NODE_ID, TOOL_NAME, "SUCCEEDED", Map.of("stdout", "alice"), null));

        NodeToolApprovalDecisionView first = service.decideToolApproval(
                approval.id(), new DecideNodeToolApprovalCommand(true), ACTOR);

        assertEquals(NodeToolApprovalStatus.APPROVED, first.approval().status());
        assertEquals("SUCCEEDED", first.approval().executionStatus());
        assertEquals("SUCCEEDED", first.execution().status());
        assertThrows(
                IllegalStateException.class,
                () -> service.decideToolApproval(approval.id(), new DecideNodeToolApprovalCommand(true), ACTOR));

        ArgumentCaptor<Map<String, Object>> arguments = ArgumentCaptor.forClass(Map.class);
        verify(sessions).invoke(eq(NODE_ID), eq(TOOL_NAME), arguments.capture(), eq(Duration.ofSeconds(45)));
        assertEquals(Map.of("command", "whoami"), arguments.getValue());
    }

    @Test
    void anotherTenantCannotDecideAnApproval() {
        ActorContext otherTenant = new ActorContext("tenant-b", "bob", Set.of(), Set.of());
        when(approvals.findByIdAndTenantId(approval.id(), otherTenant.tenantId())).thenReturn(Optional.empty());

        assertThrows(
                IllegalArgumentException.class,
                () -> service.decideToolApproval(approval.id(), new DecideNodeToolApprovalCommand(true), otherTenant));
        verify(sessions, never()).invoke(any(), any(), any(), any());
    }
}
