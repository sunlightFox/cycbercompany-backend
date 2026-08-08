package io.github.yourname.agentstudio.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yourname.agentstudio.agent.AgentCatalog;
import io.github.yourname.agentstudio.config.AppProperties;
import io.github.yourname.agentstudio.conversation.ConversationAttachmentService;
import io.github.yourname.agentstudio.conversation.ConversationService;
import io.github.yourname.agentstudio.conversation.MessageRole;
import io.github.yourname.agentstudio.knowledge.KnowledgeQueryService;
import io.github.yourname.agentstudio.model.ModelGateway;
import io.github.yourname.agentstudio.model.ModelCatalog;
import io.github.yourname.agentstudio.node.NodeToolApprovalDecisionView;
import io.github.yourname.agentstudio.node.NodeToolApprovalStatus;
import io.github.yourname.agentstudio.node.NodeToolApprovalView;
import io.github.yourname.agentstudio.node.NodeToolCallResult;
import io.github.yourname.agentstudio.node.CodingRunEvidenceView;
import io.github.yourname.agentstudio.node.NodeService;
import io.github.yourname.agentstudio.security.ActorContext;
import io.github.yourname.agentstudio.skill.SkillCatalog;
import io.github.yourname.agentstudio.skill.SkillAnalyzer;
import io.github.yourname.agentstudio.skill.SkillCompatibilityService;
import io.github.yourname.agentstudio.skill.CompatibilityReport;
import io.github.yourname.agentstudio.tool.ResolvedToolBinding;
import io.github.yourname.agentstudio.tool.RiskLevel;
import io.github.yourname.agentstudio.tool.ToolRouter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RunCommandServiceApprovalResumeTest {

    private static final ActorContext ACTOR = new ActorContext("tenant-a", "alice", java.util.Set.of(), java.util.Set.of());

    @Test
    void approvedToolResultIsRestoredIntoTheModelContextAndRunCompletes() throws Exception {
        AgentRunRepository runs = mock(AgentRunRepository.class);
        CodingRunContinuationRepository continuations = mock(CodingRunContinuationRepository.class);
        ConversationService conversations = mock(ConversationService.class);
        CodingAgentLoop codingLoop = mock(CodingAgentLoop.class);
        RunEventPublisher events = mock(RunEventPublisher.class);
        NodeService nodes = mock(NodeService.class);
        ObjectMapper mapper = new ObjectMapper();
        RunCommandService service = service(runs, continuations, conversations, codingLoop, nodes, events, mapper);

        AgentRunEntity run = new AgentRunEntity(
                "run-1", ACTOR.tenantId(), ACTOR.userId(), "conversation-1", "model-1", "agent-1", Instant.now());
        bindRunSpec(run, mapper);
        run.start();
        run.waitForApproval();
        List<ModelGateway.ModelMessage> persistedMessages = List.of(
                new ModelGateway.ModelMessage("user", "Start the local server"),
                ModelGateway.ModelMessage.assistantToolCalls("", List.of(
                        new ModelGateway.ModelToolCall("call-1", "node_tool_9", Map.of("command", "java App")))));
        CodingRunContinuationEntity continuation = new CodingRunContinuationEntity(
                run.id(),
                ACTOR.tenantId(),
                "node-1",
                "task-board",
                "approval-1",
                "call-1",
                mapper.writeValueAsString(persistedMessages),
                "[{\"chunkId\":42,\"documentId\":\"doc-1\",\"knowledgeBaseId\":\"kb-1\",\"sourceName\":\"Operations guide\",\"chunkIndex\":1,\"quote\":\"Use after approval.\",\"score\":0.9}]",
                "[]",
                Instant.now());
        when(continuations.findByRunIdAndTenantId(run.id(), ACTOR.tenantId())).thenReturn(Optional.of(continuation));
        when(runs.findByIdAndTenantId(run.id(), ACTOR.tenantId())).thenReturn(Optional.of(run));
        when(codingLoop.resume(eq(run.id()), eq("model-1"), any(), any(), eq(ACTOR), any(), any(), any()))
                .thenReturn("Server started and verified. [K1]");
        when(nodes.codingEvidence(run.id(), ACTOR)).thenReturn(verifiedEvidence());

        NodeToolApprovalView approval = new NodeToolApprovalView(
                "approval-1",
                "node-1",
                "process.start",
                run.id(),
                "call-1",
                "{\"command\":\"java App\"}",
                30,
                NodeToolApprovalStatus.APPROVED,
                "alice",
                "alice",
                Instant.now(),
                Instant.now(),
                Instant.now(),
                "SUCCEEDED",
                "{\"pid\":123}",
                null);
        NodeToolApprovalDecisionView decision = new NodeToolApprovalDecisionView(
                approval,
                new NodeToolCallResult("nodeinv-1", "node-1", "process.start", "SUCCEEDED", Map.of("pid", 123), null));

        service.resumeAfterToolApproval(decision, ACTOR);

        assertThat(run.status()).isEqualTo(RunStatus.RUNNING);
        verify(continuations).delete(continuation);
        verify(events).publish(run.id(), RunEventType.RUN_RESUMED, "approvalId=approval-1", ACTOR);
        ArgumentCaptor<List<ModelGateway.ModelMessage>> restored = ArgumentCaptor.forClass(List.class);
        verify(codingLoop, timeout(2_000)).resume(
                eq(run.id()), eq("model-1"), any(), restored.capture(), eq(ACTOR), any(), any(), any());
        assertThat(restored.getValue()).anyMatch(message ->
                "tool".equals(message.role())
                        && "call-1".equals(message.toolCallId())
                        && message.content().contains("SUCCEEDED"));
        verify(conversations, timeout(2_000)).append(
                "conversation-1", MessageRole.ASSISTANT, "Server started and verified. [K1]", run.id(), ACTOR);
        verify(events, timeout(2_000)).publish(
                eq(run.id()), eq(RunEventType.RETRIEVAL_SOURCES),
                org.mockito.ArgumentMatchers.contains("knowledge-42"), eq(ACTOR));
        assertThat(run.status()).isEqualTo(RunStatus.SUCCEEDED);
    }

    @Test
    void approvedNodeToolRecordsItsActualExecutionOutcomeInWorkflowCheckpoints() throws Exception {
        AgentRunRepository runs = mock(AgentRunRepository.class);
        CodingRunContinuationRepository continuations = mock(CodingRunContinuationRepository.class);
        ConversationService conversations = mock(ConversationService.class);
        CodingAgentLoop codingLoop = mock(CodingAgentLoop.class);
        RunEventPublisher events = mock(RunEventPublisher.class);
        NodeService nodes = mock(NodeService.class);
        ObjectMapper mapper = new ObjectMapper();
        RunCommandService service = service(runs, continuations, conversations, codingLoop, nodes, events, mapper);
        RunWorkflowCheckpointService checkpoints = mock(RunWorkflowCheckpointService.class);
        service.configureWorkflowCheckpoints(checkpoints);

        AgentRunEntity run = new AgentRunEntity(
                "run-checkpoint", ACTOR.tenantId(), ACTOR.userId(), "conversation-1", "model-1", "agent-1", Instant.now());
        bindRunSpec(run, mapper);
        run.start();
        run.waitForApproval();
        CodingRunContinuationEntity continuation = new CodingRunContinuationEntity(
                run.id(), ACTOR.tenantId(), "node-1", "task-board", "approval-1", "call-1", "[]", Instant.now());
        when(continuations.findByRunIdAndTenantId(run.id(), ACTOR.tenantId())).thenReturn(Optional.of(continuation));
        when(runs.findByIdAndTenantId(run.id(), ACTOR.tenantId())).thenReturn(Optional.of(run));

        service.resumeAfterToolApproval(approvedDecision(run), ACTOR);

        verify(checkpoints).toolFinished(run.id(), ACTOR, "fs.write", true, null);
    }

    @Test
    void failedApprovedNodeToolRecordsFailureInWorkflowCheckpoints() throws Exception {
        AgentRunRepository runs = mock(AgentRunRepository.class);
        CodingRunContinuationRepository continuations = mock(CodingRunContinuationRepository.class);
        ConversationService conversations = mock(ConversationService.class);
        CodingAgentLoop codingLoop = mock(CodingAgentLoop.class);
        RunEventPublisher events = mock(RunEventPublisher.class);
        NodeService nodes = mock(NodeService.class);
        ObjectMapper mapper = new ObjectMapper();
        RunCommandService service = service(runs, continuations, conversations, codingLoop, nodes, events, mapper);
        RunWorkflowCheckpointService checkpoints = mock(RunWorkflowCheckpointService.class);
        service.configureWorkflowCheckpoints(checkpoints);

        AgentRunEntity run = new AgentRunEntity(
                "run-failed-checkpoint", ACTOR.tenantId(), ACTOR.userId(), "conversation-1", "model-1", "agent-1", Instant.now());
        bindRunSpec(run, mapper);
        run.start();
        run.waitForApproval();
        CodingRunContinuationEntity continuation = new CodingRunContinuationEntity(
                run.id(), ACTOR.tenantId(), "node-1", "task-board", "approval-1", "call-1", "[]", Instant.now());
        when(continuations.findByRunIdAndTenantId(run.id(), ACTOR.tenantId())).thenReturn(Optional.of(continuation));
        when(runs.findByIdAndTenantId(run.id(), ACTOR.tenantId())).thenReturn(Optional.of(run));
        NodeToolApprovalDecisionView decision = new NodeToolApprovalDecisionView(
                approvedDecision(run).approval(),
                new NodeToolCallResult("nodeinv-1", "node-1", "fs.write", "FAILED", Map.of(), "permission denied"));

        service.resumeAfterToolApproval(decision, ACTOR);

        verify(checkpoints).toolFinished(run.id(), ACTOR, "fs.write", false, "permission denied");
    }

    @Test
    void resumedCodingCannotReportSuccessWhenTheServerFindsMissingVerification() throws Exception {
        AgentRunRepository runs = mock(AgentRunRepository.class);
        CodingRunContinuationRepository continuations = mock(CodingRunContinuationRepository.class);
        ConversationService conversations = mock(ConversationService.class);
        CodingAgentLoop codingLoop = mock(CodingAgentLoop.class);
        RunEventPublisher events = mock(RunEventPublisher.class);
        NodeService nodes = mock(NodeService.class);
        ObjectMapper mapper = new ObjectMapper();
        RunCommandService service = service(runs, continuations, conversations, codingLoop, nodes, events, mapper);

        AgentRunEntity run = new AgentRunEntity(
                "run-unverified", ACTOR.tenantId(), ACTOR.userId(), "conversation-1", "model-1", "agent-1", Instant.now());
        bindRunSpec(run, mapper);
        run.start();
        run.waitForApproval();
        CodingRunContinuationEntity continuation = new CodingRunContinuationEntity(
                run.id(), ACTOR.tenantId(), "node-1", "task-board", "approval-1", "call-1",
                mapper.writeValueAsString(List.of(new ModelGateway.ModelMessage("user", "Implement the endpoint"))), Instant.now());
        when(continuations.findByRunIdAndTenantId(run.id(), ACTOR.tenantId())).thenReturn(Optional.of(continuation));
        when(runs.findByIdAndTenantId(run.id(), ACTOR.tenantId())).thenReturn(Optional.of(run));
        when(codingLoop.resume(eq(run.id()), eq("model-1"), any(), any(), eq(ACTOR), any()))
                .thenReturn("Implementation complete.");
        when(nodes.codingEvidence(run.id(), ACTOR)).thenReturn(new CodingRunEvidenceView(
                run.id(), 1, List.of("src/App.java"), List.of(), List.of(), List.of(), false, List.of()));

        service.resumeAfterToolApproval(approvedDecision(run), ACTOR);

        verify(conversations, timeout(2_000)).append(
                eq("conversation-1"), eq(MessageRole.ASSISTANT),
                org.mockito.ArgumentMatchers.contains("尚未被标记为完成"), eq(run.id()), eq(ACTOR));
        assertThat(run.status()).isEqualTo(RunStatus.NEEDS_VERIFICATION);
        assertThat(run.errorMessage()).contains("没有成功的构建、测试或命令验证证据");
        verify(events, timeout(2_000)).publish(
                eq(run.id()), eq(RunEventType.RUN_NEEDS_VERIFICATION), org.mockito.ArgumentMatchers.contains("交付门禁未通过"), eq(ACTOR));
    }

    @Test
    void rejectedNodeApprovalCompletesWithoutCallingTheModel() throws Exception {
        AgentRunRepository runs = mock(AgentRunRepository.class);
        CodingRunContinuationRepository continuations = mock(CodingRunContinuationRepository.class);
        ConversationService conversations = mock(ConversationService.class);
        CodingAgentLoop codingLoop = mock(CodingAgentLoop.class);
        RunEventPublisher events = mock(RunEventPublisher.class);
        ObjectMapper mapper = new ObjectMapper();
        RunCommandService service = service(
                runs, continuations, conversations, codingLoop, mock(NodeService.class), events, mapper);
        AgentRunEntity run = new AgentRunEntity(
                "run-rejected", ACTOR.tenantId(), ACTOR.userId(), "conversation-1", "model-1", "agent-1", Instant.now());
        bindRunSpec(run, mapper);
        run.start();
        run.waitForApproval();
        CodingRunContinuationEntity continuation = new CodingRunContinuationEntity(
                run.id(), ACTOR.tenantId(), "node-1", "", "approval-rejected", "call-rejected", "[]", Instant.now());
        when(continuations.findByRunIdAndTenantId(run.id(), ACTOR.tenantId())).thenReturn(Optional.of(continuation));
        when(runs.findByIdAndTenantId(run.id(), ACTOR.tenantId())).thenReturn(Optional.of(run));
        NodeToolApprovalView approval = new NodeToolApprovalView(
                "approval-rejected", "node-1", "system.shell.run", run.id(), "call-rejected", "{}", 30,
                NodeToolApprovalStatus.REJECTED, "alice", "alice", Instant.now(), Instant.now(), null,
                null, null, null);

        service.resumeAfterToolApproval(new NodeToolApprovalDecisionView(approval, null), ACTOR);

        assertThat(run.status()).isEqualTo(RunStatus.SUCCEEDED);
        verify(continuations).delete(continuation);
        verify(conversations).append(
                "conversation-1", MessageRole.ASSISTANT,
                "Tool execution was rejected. The requested command was not run.", run.id(), ACTOR);
        verify(events).publish(
                run.id(), RunEventType.FINAL_ANSWER,
                "Tool execution was rejected. The requested command was not run.", ACTOR);
        verify(codingLoop, org.mockito.Mockito.never()).resume(
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void cancellationMarksRunTerminalAndCleansUpItsProcesses() {
        AgentRunRepository runs = mock(AgentRunRepository.class);
        CodingRunContinuationRepository continuations = mock(CodingRunContinuationRepository.class);
        ConversationService conversations = mock(ConversationService.class);
        CodingAgentLoop codingLoop = mock(CodingAgentLoop.class);
        RunEventPublisher events = mock(RunEventPublisher.class);
        RunCommandService service = service(
                runs, continuations, conversations, codingLoop, mock(NodeService.class), events, new ObjectMapper());
        AgentRunEntity run = new AgentRunEntity(
                "run-cancel", ACTOR.tenantId(), ACTOR.userId(), "conversation-1", "model-1", "agent-1", Instant.now());
        run.start();
        when(runs.findByIdAndTenantId(run.id(), ACTOR.tenantId())).thenReturn(Optional.of(run));
        when(continuations.findByRunIdAndTenantId(run.id(), ACTOR.tenantId())).thenReturn(Optional.empty());

        RunView view = service.cancel(run.id(), ACTOR);

        assertThat(view.status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(run.status()).isEqualTo(RunStatus.CANCELLED);
        verify(codingLoop).cleanupManagedProcesses(run.id(), ACTOR);
        verify(events).publish(run.id(), RunEventType.RUN_CANCELLED, "Run cancelled by user.", ACTOR);
    }

    private static RunCommandService service(
            AgentRunRepository runs,
            CodingRunContinuationRepository continuations,
            ConversationService conversations,
            CodingAgentLoop codingLoop,
            NodeService nodes,
            RunEventPublisher events,
            ObjectMapper mapper) {
        return new RunCommandService(
                mock(AppProperties.class),
                runs,
                continuations,
                conversations,
                mock(ConversationAttachmentService.class),
                mock(KnowledgeQueryService.class),
                mock(AgentCatalog.class),
                mock(SkillCatalog.class),
                mock(SkillAnalyzer.class),
                mock(SkillCompatibilityService.class),
                mock(ModelCatalog.class),
                mock(ModelGateway.class),
                codingLoop,
                mock(ToolRouter.class),
                nodes,
                new RunExecutionRegistry(),
                new ConversationRunQueue(),
                events,
                mapper);
    }

    private static CodingRunEvidenceView verifiedEvidence() {
        return new CodingRunEvidenceView(
                "ignored", 1, List.of(), List.of("shell.run"), List.of("test"), List.of(), false, List.of());
    }

    private static NodeToolApprovalDecisionView approvedDecision(AgentRunEntity run) {
        NodeToolApprovalView approval = new NodeToolApprovalView(
                "approval-1", "node-1", "fs.write", run.id(), "call-1", "{\"path\":\"src/App.java\"}",
                30, NodeToolApprovalStatus.APPROVED, "alice", "alice", Instant.now(), Instant.now(), Instant.now(),
                "SUCCEEDED", "{\"written\":true}", null);
        return new NodeToolApprovalDecisionView(
                approval, new NodeToolCallResult("nodeinv-1", "node-1", "fs.write", "SUCCEEDED", Map.of(), null));
    }

    private static void bindRunSpec(AgentRunEntity run, ObjectMapper mapper) throws Exception {
        ResolvedToolBinding binding = new ResolvedToolBinding(
                "node:node-1:fs.write", "tool_fs_write", "fs.write", "node", "fs.write",
                "Write", RiskLevel.HIGH, true, Map.of("type", "object"), Map.of("nodeId", "node-1"));
        RunSpec spec = new RunSpec(
                RunSpec.CURRENT_VERSION,
                run.conversationId(),
                "Implement",
                run.modelProfileId(),
                "sha256:model",
                run.agentId(),
                "agent-version-1",
                "sha256:manifest",
                "Agent prompt",
                "sha256:prompt",
                "node:*",
                "{}",
                List.of(),
                List.of(),
                "sha256:skills",
                "sha256:instructions",
                List.of(),
                new CompatibilityReport(true, List.of(), List.of(), List.of(), List.of()),
                List.of(),
                List.of(),
                List.of("fs.write"),
                List.of(binding),
                "node-1",
                RunExecutionMode.CODING,
                "task-board",
                List.of(),
                "",
                "sha256:capabilities",
                "sha256:policy",
                "on-request",
                ACTOR.tenantId(),
                ACTOR.userId(),
                ACTOR.roles(),
                ACTOR.scopes(),
                List.of(),
                "",
                "{}");
        String json = mapper.writeValueAsString(spec);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8));
        run.bindRunSpec(json, "sha256:" + HexFormat.of().formatHex(digest));
    }
}
