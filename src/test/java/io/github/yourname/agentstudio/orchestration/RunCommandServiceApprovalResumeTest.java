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
import io.github.yourname.agentstudio.conversation.ConversationService;
import io.github.yourname.agentstudio.conversation.MessageRole;
import io.github.yourname.agentstudio.knowledge.KnowledgeQueryService;
import io.github.yourname.agentstudio.mcp.McpConnectionService;
import io.github.yourname.agentstudio.model.ModelGateway;
import io.github.yourname.agentstudio.model.ModelCatalog;
import io.github.yourname.agentstudio.node.NodeToolApprovalDecisionView;
import io.github.yourname.agentstudio.node.NodeToolApprovalStatus;
import io.github.yourname.agentstudio.node.NodeToolApprovalView;
import io.github.yourname.agentstudio.node.NodeToolCallResult;
import io.github.yourname.agentstudio.security.ActorContext;
import io.github.yourname.agentstudio.tool.WebSearchService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        ObjectMapper mapper = new ObjectMapper();
        RunCommandService service = service(runs, continuations, conversations, codingLoop, events, mapper);

        AgentRunEntity run = new AgentRunEntity(
                "run-1", ACTOR.tenantId(), ACTOR.userId(), "conversation-1", "model-1", "agent-1", Instant.now());
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
                "approval-1",
                "call-1",
                mapper.writeValueAsString(persistedMessages),
                Instant.now());
        when(continuations.findByRunIdAndTenantId(run.id(), ACTOR.tenantId())).thenReturn(Optional.of(continuation));
        when(runs.findByIdAndTenantId(run.id(), ACTOR.tenantId())).thenReturn(Optional.of(run));
        when(codingLoop.resume(eq(run.id()), eq("model-1"), eq("node-1"), any(), eq(ACTOR)))
                .thenReturn("Server started and verified.");

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
        verify(codingLoop, timeout(2_000)).resume(eq(run.id()), eq("model-1"), eq("node-1"), restored.capture(), eq(ACTOR));
        assertThat(restored.getValue()).anyMatch(message ->
                "tool".equals(message.role())
                        && "call-1".equals(message.toolCallId())
                        && message.content().contains("SUCCEEDED"));
        verify(conversations, timeout(2_000)).append(
                "conversation-1", MessageRole.ASSISTANT, "Server started and verified.", run.id(), ACTOR);
        assertThat(run.status()).isEqualTo(RunStatus.SUCCEEDED);
    }

    private static RunCommandService service(
            AgentRunRepository runs,
            CodingRunContinuationRepository continuations,
            ConversationService conversations,
            CodingAgentLoop codingLoop,
            RunEventPublisher events,
            ObjectMapper mapper) {
        return new RunCommandService(
                mock(AppProperties.class),
                runs,
                continuations,
                conversations,
                mock(AgentCatalog.class),
                mock(KnowledgeQueryService.class),
                mock(WebSearchService.class),
                mock(McpConnectionService.class),
                mock(ModelCatalog.class),
                mock(ModelGateway.class),
                codingLoop,
                events,
                mapper);
    }
}
