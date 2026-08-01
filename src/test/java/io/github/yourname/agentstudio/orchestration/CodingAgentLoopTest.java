package io.github.yourname.agentstudio.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.yourname.agentstudio.model.ModelGateway;
import io.github.yourname.agentstudio.model.ModelRateLimitException;
import io.github.yourname.agentstudio.security.ActorContext;
import io.github.yourname.agentstudio.tool.ResolvedToolBinding;
import io.github.yourname.agentstudio.tool.RiskLevel;
import io.github.yourname.agentstudio.tool.ToolProviderResult;
import io.github.yourname.agentstudio.tool.ToolRouter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CodingAgentLoopTest {

    @Test
    void compactsOldToolOutputButPreservesTaskInstructionsAndRecentState() {
        List<ModelGateway.ModelMessage> messages = new ArrayList<>();
        messages.add(new ModelGateway.ModelMessage("system", "System workflow"));
        messages.add(new ModelGateway.ModelMessage("user", "Fix the project"));
        for (int index = 0; index < 12; index++) {
            messages.add(ModelGateway.ModelMessage.toolResult("old-" + index, "x".repeat(10_000)));
        }
        messages.add(ModelGateway.ModelMessage.toolResult("recent", "recent verification result"));

        CodingAgentLoop.compactContextIfNeeded(messages);

        assertThat(messages).anyMatch(message -> "System workflow".equals(message.content()));
        assertThat(messages).anyMatch(message -> "Fix the project".equals(message.content()));
        assertThat(messages).anyMatch(message -> message.content() != null && message.content().contains("Earlier tool history was compacted"));
        assertThat(messages).anyMatch(message -> "recent verification result".equals(message.content()));
        assertThat(messages.stream().mapToInt(message -> message.content() == null ? 0 : message.content().length()).sum())
                .isLessThan(60_000);
    }

    @Test
    void cancelledRunDoesNotAskTheModelOrInvokeTools() {
        ModelGateway modelGateway = mock(ModelGateway.class);
        ToolRouter tools = mock(ToolRouter.class);
        RunExecutionRegistry executions = new RunExecutionRegistry();
        executions.cancel("run-cancelled");
        ActorContext actor = new ActorContext("tenant-a", "user-a", java.util.Set.of(), java.util.Set.of());
        CodingAgentLoop loop = new CodingAgentLoop(
                modelGateway, tools, mock(RunEventPublisher.class), executions, duration -> { });

        assertThatThrownBy(() -> loop.execute(
                "run-cancelled", "model-a", List.of(), new java.util.ArrayList<>(), actor))
                .hasMessageContaining("was cancelled");
        verifyNoInteractions(modelGateway);
    }

    @Test
    void rejectsAPlainTextResponseBeforeAnyCodingToolWasCalled() {
        ModelGateway gateway = mock(ModelGateway.class);
        ToolRouter tools = mock(ToolRouter.class);
        RunEventPublisher events = mock(RunEventPublisher.class);
        ActorContext actor = new ActorContext("tenant-a", "user-a", java.util.Set.of(), java.util.Set.of());
        var declaredTool = binding("node_tool_7", "fs.read");
        when(gateway.complete(any())).thenReturn(new ModelGateway.ModelAnswer("I will inspect it.", null, null, "test"));

        assertThatThrownBy(() -> new CodingAgentLoop(gateway, tools, events).execute(
                        "run-a", "model-a", List.of(declaredTool), new java.util.ArrayList<>(), actor))
                .hasMessageContaining("without calling any coding tool");
    }

    @Test
    void feedsNodeToolResultBackToModelBeforeFinalAnswer() {
        ModelGateway gateway = mock(ModelGateway.class);
        ToolRouter tools = mock(ToolRouter.class);
        RunEventPublisher events = mock(RunEventPublisher.class);
        ActorContext actor = new ActorContext("tenant-a", "user-a", java.util.Set.of(), java.util.Set.of());
        var declaredTool = binding("node_tool_7", "fs.read");
        when(gateway.complete(any()))
                .thenReturn(new ModelGateway.ModelAnswer(
                        "I will inspect the file.",
                        null,
                        null,
                        "test-model",
                        List.of(new ModelGateway.ModelToolCall("call-1", "node_tool_7", Map.of("path", "README.md"))),
                        "tool_calls"))
                .thenReturn(new ModelGateway.ModelAnswer("The fix is verified.", null, null, "test-model"));
        when(tools.invoke(any()))
                .thenReturn(new ToolProviderResult("SUCCEEDED", true, Map.of(), "", null));
        when(tools.cleanup("run-a", actor)).thenReturn(List.of());

        String answer = new CodingAgentLoop(gateway, tools, events).execute(
                "run-a",
                "model-a",
                List.of(declaredTool),
                new java.util.ArrayList<>(List.of(new ModelGateway.ModelMessage("user", "Inspect README"))),
                actor);

        assertThat(answer).isEqualTo("The fix is verified.");
        verify(tools).invoke(any());
        verify(events).publish("run-a", RunEventType.TOOL_CALL_COMPLETED, "tool=fs.read", actor);

        ArgumentCaptor<ModelGateway.ModelCompletionRequest> requests = ArgumentCaptor.forClass(ModelGateway.ModelCompletionRequest.class);
        verify(gateway, times(2)).complete(requests.capture());
        assertThat(requests.getAllValues().getFirst().toolChoice()).isEqualTo(ModelGateway.ToolChoice.REQUIRED);
        assertThat(requests.getAllValues().get(1).toolChoice()).isEqualTo(ModelGateway.ToolChoice.AUTO);
        assertThat(requests.getAllValues().get(1).messages())
                .anyMatch(message -> "tool".equals(message.role()) && "call-1".equals(message.toolCallId()));
        verify(tools).cleanup("run-a", actor);
    }

    @Test
    void retriesProviderRateLimitsWithoutRepeatingToolExecution() {
        ModelGateway gateway = mock(ModelGateway.class);
        ToolRouter tools = mock(ToolRouter.class);
        RunEventPublisher events = mock(RunEventPublisher.class);
        ActorContext actor = new ActorContext("tenant-a", "user-a", java.util.Set.of(), java.util.Set.of());
        var declaredTool = binding("node_tool_7", "fs.read");
        when(gateway.complete(any()))
                .thenThrow(new ModelRateLimitException("provider limited", Duration.ofSeconds(2), null))
                .thenReturn(new ModelGateway.ModelAnswer(
                        "Inspecting.",
                        null,
                        null,
                        "test-model",
                        List.of(new ModelGateway.ModelToolCall("call-1", "node_tool_7", Map.of("path", "README.md"))),
                        "tool_calls"))
                .thenReturn(new ModelGateway.ModelAnswer("Verified after retry.", null, null, "test-model"));
        when(tools.invoke(any()))
                .thenReturn(new ToolProviderResult("SUCCEEDED", true, Map.of(), "", null));
        List<Duration> delays = new ArrayList<>();

        String answer = new CodingAgentLoop(gateway, tools, events, delays::add).execute(
                "run-a",
                "model-a",
                List.of(declaredTool),
                new ArrayList<>(List.of(new ModelGateway.ModelMessage("user", "Inspect README"))),
                actor);

        assertThat(answer).isEqualTo("Verified after retry.");
        assertThat(delays).containsExactly(Duration.ofSeconds(2));
        verify(events).publish("run-a", RunEventType.MODEL_RATE_LIMITED, "retry=1, delaySeconds=2", actor);
        verify(tools, times(1)).invoke(any());
    }

    @Test
    void pausesForApprovalWithoutCleaningUpManagedProcesses() {
        ModelGateway gateway = mock(ModelGateway.class);
        ToolRouter tools = mock(ToolRouter.class);
        RunEventPublisher events = mock(RunEventPublisher.class);
        ActorContext actor = new ActorContext("tenant-a", "user-a", java.util.Set.of(), java.util.Set.of());
        var declaredTool = binding("node_tool_9", "process.start");
        when(gateway.complete(any())).thenReturn(new ModelGateway.ModelAnswer(
                "I need to start the server.",
                null,
                null,
                "test-model",
                List.of(new ModelGateway.ModelToolCall("call-approval", "node_tool_9", Map.of("command", "java App"))),
                "tool_calls"));
        when(tools.invoke(any()))
                .thenReturn(new ToolProviderResult(
                        "APPROVAL_REQUIRED", false, Map.of(), "", "approval-1"));

        List<ModelGateway.ModelMessage> messages = new ArrayList<>(List.of(new ModelGateway.ModelMessage("user", "Start it")));
        assertThatThrownBy(() -> new CodingAgentLoop(gateway, tools, events).execute(
                        "run-a", "model-a", List.of(declaredTool), messages, actor))
                .isInstanceOfSatisfying(CodingApprovalRequiredException.class, exception -> {
                    assertThat(exception.approvalId()).isEqualTo("approval-1");
                    assertThat(exception.toolCallId()).isEqualTo("call-approval");
                    assertThat(exception.messages()).anyMatch(message ->
                            "assistant".equals(message.role()) && !message.toolCalls().isEmpty());
                });

        verify(events).publish(
                "run-a",
                RunEventType.TOOL_APPROVAL_REQUIRED,
                "tool=process.start, approvalId=approval-1",
                actor);
        verify(tools, never()).cleanup("run-a", actor);
    }

    @Test
    void resumedRunMayReturnFinalAnswerWithoutAnotherToolCall() {
        ModelGateway gateway = mock(ModelGateway.class);
        ToolRouter tools = mock(ToolRouter.class);
        RunEventPublisher events = mock(RunEventPublisher.class);
        ActorContext actor = new ActorContext("tenant-a", "user-a", java.util.Set.of(), java.util.Set.of());
        var declaredTool = binding("node_tool_7", "fs.read");
        when(gateway.complete(any())).thenReturn(new ModelGateway.ModelAnswer("The approved command is complete.", null, null, "test"));
        when(tools.cleanup("run-a", actor)).thenReturn(List.of());

        String answer = new CodingAgentLoop(gateway, tools, events).resume(
                "run-a",
                "model-a",
                List.of(declaredTool),
                new ArrayList<>(List.of(
                        new ModelGateway.ModelMessage("user", "Start it"),
                        ModelGateway.ModelMessage.toolResult("call-approval", "{\"status\":\"SUCCEEDED\"}"))),
                actor);

        assertThat(answer).isEqualTo("The approved command is complete.");
        ArgumentCaptor<ModelGateway.ModelCompletionRequest> request = ArgumentCaptor.forClass(ModelGateway.ModelCompletionRequest.class);
        verify(gateway).complete(request.capture());
        assertThat(request.getValue().toolChoice()).isEqualTo(ModelGateway.ToolChoice.AUTO);
    }

    private static ResolvedToolBinding binding(String modelName, String logicalName) {
        return new ResolvedToolBinding(
                "node:node-a:" + logicalName,
                modelName,
                logicalName,
                "node",
                logicalName,
                "Node tool " + logicalName,
                RiskLevel.LOW,
                false,
                Map.of("type", "object"),
                Map.of("nodeId", "node-a"));
    }
}
