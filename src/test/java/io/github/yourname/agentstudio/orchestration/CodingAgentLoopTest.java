package io.github.yourname.agentstudio.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.yourname.agentstudio.model.ModelGateway;
import io.github.yourname.agentstudio.model.ModelRateLimitException;
import io.github.yourname.agentstudio.security.ActorContext;
import io.github.yourname.agentstudio.tool.CodingToolAdapter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CodingAgentLoopTest {

    @Test
    void rejectsAPlainTextResponseBeforeAnyCodingToolWasCalled() {
        ModelGateway gateway = mock(ModelGateway.class);
        CodingToolAdapter tools = mock(CodingToolAdapter.class);
        RunEventPublisher events = mock(RunEventPublisher.class);
        ActorContext actor = new ActorContext("tenant-a", "user-a", java.util.Set.of(), java.util.Set.of());
        var declaredTool = new CodingToolAdapter.AvailableTool(
                "node_tool_7", "node-a", "fs.read", new ModelGateway.ModelTool("node_tool_7", "Read", Map.of()));
        when(tools.availableTools("node-a", actor)).thenReturn(List.of(declaredTool));
        when(gateway.complete(any())).thenReturn(new ModelGateway.ModelAnswer("I will inspect it.", null, null, "test"));

        assertThatThrownBy(() -> new CodingAgentLoop(gateway, tools, events).execute(
                        "run-a", "model-a", "node-a", new java.util.ArrayList<>(), actor))
                .hasMessageContaining("without calling any coding tool");
    }

    @Test
    void feedsNodeToolResultBackToModelBeforeFinalAnswer() {
        ModelGateway gateway = mock(ModelGateway.class);
        CodingToolAdapter tools = mock(CodingToolAdapter.class);
        RunEventPublisher events = mock(RunEventPublisher.class);
        ActorContext actor = new ActorContext("tenant-a", "user-a", java.util.Set.of(), java.util.Set.of());
        var declaredTool = new CodingToolAdapter.AvailableTool(
                "node_tool_7",
                "node-a",
                "fs.read",
                new ModelGateway.ModelTool("node_tool_7", "Read a workspace file.", Map.of("type", "object")));

        when(tools.availableTools("node-a", actor)).thenReturn(List.of(declaredTool));
        when(gateway.complete(any()))
                .thenReturn(new ModelGateway.ModelAnswer(
                        "I will inspect the file.",
                        null,
                        null,
                        "test-model",
                        List.of(new ModelGateway.ModelToolCall("call-1", "node_tool_7", Map.of("path", "README.md"))),
                        "tool_calls"))
                .thenReturn(new ModelGateway.ModelAnswer("The fix is verified.", null, null, "test-model"));
        when(tools.execute(eq("run-a"), eq(declaredTool), any(), eq(actor)))
                .thenReturn(new CodingToolAdapter.ToolExecution(true, "{\"status\":\"SUCCEEDED\",\"result\":{}}"));

        String answer = new CodingAgentLoop(gateway, tools, events).execute(
                "run-a",
                "model-a",
                "node-a",
                new java.util.ArrayList<>(List.of(new ModelGateway.ModelMessage("user", "Inspect README"))),
                actor);

        assertThat(answer).isEqualTo("The fix is verified.");
        verify(tools).execute(eq("run-a"), eq(declaredTool), any(), eq(actor));
        verify(events).publish("run-a", RunEventType.TOOL_CALL_COMPLETED, "tool=fs.read", actor);

        ArgumentCaptor<ModelGateway.ModelCompletionRequest> requests = ArgumentCaptor.forClass(ModelGateway.ModelCompletionRequest.class);
        verify(gateway, times(2)).complete(requests.capture());
        assertThat(requests.getAllValues().getFirst().toolChoice()).isEqualTo(ModelGateway.ToolChoice.REQUIRED);
        assertThat(requests.getAllValues().get(1).toolChoice()).isEqualTo(ModelGateway.ToolChoice.AUTO);
        assertThat(requests.getAllValues().get(1).messages())
                .anyMatch(message -> "tool".equals(message.role()) && "call-1".equals(message.toolCallId()));
    }

    @Test
    void retriesProviderRateLimitsWithoutRepeatingToolExecution() {
        ModelGateway gateway = mock(ModelGateway.class);
        CodingToolAdapter tools = mock(CodingToolAdapter.class);
        RunEventPublisher events = mock(RunEventPublisher.class);
        ActorContext actor = new ActorContext("tenant-a", "user-a", java.util.Set.of(), java.util.Set.of());
        var declaredTool = new CodingToolAdapter.AvailableTool(
                "node_tool_7",
                "node-a",
                "fs.read",
                new ModelGateway.ModelTool("node_tool_7", "Read a workspace file.", Map.of("type", "object")));
        when(tools.availableTools("node-a", actor)).thenReturn(List.of(declaredTool));
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
        when(tools.execute(eq("run-a"), eq(declaredTool), any(), eq(actor)))
                .thenReturn(new CodingToolAdapter.ToolExecution(true, "{\"status\":\"SUCCEEDED\"}"));
        List<Duration> delays = new ArrayList<>();

        String answer = new CodingAgentLoop(gateway, tools, events, delays::add).execute(
                "run-a",
                "model-a",
                "node-a",
                new ArrayList<>(List.of(new ModelGateway.ModelMessage("user", "Inspect README"))),
                actor);

        assertThat(answer).isEqualTo("Verified after retry.");
        assertThat(delays).containsExactly(Duration.ofSeconds(2));
        verify(events).publish("run-a", RunEventType.MODEL_RATE_LIMITED, "retry=1, delaySeconds=2", actor);
        verify(tools, times(1)).execute(eq("run-a"), eq(declaredTool), any(), eq(actor));
    }
}
