package io.github.yourname.agentstudio.orchestration;

import io.github.yourname.agentstudio.model.ModelGateway;
import io.github.yourname.agentstudio.model.ModelRateLimitException;
import io.github.yourname.agentstudio.security.ActorContext;
import io.github.yourname.agentstudio.tool.CodingToolAdapter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** A bounded native-tool loop for repository editing and verification work. */
@Service
class CodingAgentLoop {

    private static final int MAX_MODEL_TURNS = 16;
    private static final int MAX_TOOL_CALLS = 32;
    private static final int MAX_RATE_LIMIT_RETRIES = 3;
    private static final Duration INITIAL_RATE_LIMIT_DELAY = Duration.ofSeconds(15);
    private static final Duration MAX_RATE_LIMIT_DELAY = Duration.ofSeconds(45);

    private final ModelGateway modelGateway;
    private final CodingToolAdapter tools;
    private final RunEventPublisher events;
    private final RetrySleeper retrySleeper;

    @Autowired
    CodingAgentLoop(ModelGateway modelGateway, CodingToolAdapter tools, RunEventPublisher events) {
        this(modelGateway, tools, events, Thread::sleep);
    }

    CodingAgentLoop(
            ModelGateway modelGateway,
            CodingToolAdapter tools,
            RunEventPublisher events,
            RetrySleeper retrySleeper) {
        this.modelGateway = modelGateway;
        this.tools = tools;
        this.events = events;
        this.retrySleeper = retrySleeper;
    }

    String execute(
            String runId,
            String modelProfileId,
            String nodeId,
            List<ModelGateway.ModelMessage> messages,
            ActorContext actor) {
        List<CodingToolAdapter.AvailableTool> available = tools.availableTools(nodeId, actor);
        if (available.isEmpty()) {
            throw new IllegalArgumentException("The selected node has no enabled tools approved for autonomous runs.");
        }
        Map<String, CodingToolAdapter.AvailableTool> byModelName = new HashMap<>();
        for (CodingToolAdapter.AvailableTool tool : available) {
            byModelName.put(tool.modelToolName(), tool);
        }
        List<ModelGateway.ModelTool> modelTools = available.stream()
                .map(CodingToolAdapter.AvailableTool::modelTool)
                .toList();
        int executedCalls = 0;

        for (int turn = 1; turn <= MAX_MODEL_TURNS; turn++) {
            var answer = completeWithRateLimitRetry(
                    runId,
                    actor,
                    new ModelGateway.ModelCompletionRequest(
                            modelProfileId,
                            messages,
                            modelTools,
                            turn == 1 ? ModelGateway.ToolChoice.REQUIRED : ModelGateway.ToolChoice.AUTO));
            List<ModelGateway.ModelToolCall> calls = normalizeCalls(answer.toolCalls());
            if (calls.isEmpty()) {
                if (executedCalls == 0) {
                    throw new IllegalStateException(
                            "The selected model returned a text response without calling any coding tool. "
                                    + "Use a model/provider with verified OpenAI-compatible function calling.");
                }
                return answer.content() == null ? "" : answer.content();
            }

            messages.add(ModelGateway.ModelMessage.assistantToolCalls(answer.content(), calls));
            for (ModelGateway.ModelToolCall call : calls) {
                if (++executedCalls > MAX_TOOL_CALLS) {
                    throw new IllegalStateException("Coding run reached its maximum of " + MAX_TOOL_CALLS + " tool calls.");
                }
                CodingToolAdapter.AvailableTool tool = byModelName.get(call.name());
                if (tool == null) {
                    String result = "{\"status\":\"FAILED\",\"error\":\"Tool is not available in this run.\"}";
                    events.publish(runId, RunEventType.TOOL_CALL_FAILED, "Unknown tool requested: " + call.name(), actor);
                    messages.add(ModelGateway.ModelMessage.toolResult(call.id(), result));
                    continue;
                }
                events.publish(runId, RunEventType.TOOL_CALL_REQUESTED, "tool=" + tool.nodeToolName(), actor);
                events.publish(runId, RunEventType.TOOL_CALL_STARTED, "tool=" + tool.nodeToolName(), actor);
                CodingToolAdapter.ToolExecution outcome = tools.execute(runId, tool, call, actor);
                events.publish(
                        runId,
                        outcome.succeeded() ? RunEventType.TOOL_CALL_COMPLETED : RunEventType.TOOL_CALL_FAILED,
                        "tool=" + tool.nodeToolName(),
                        actor);
                messages.add(ModelGateway.ModelMessage.toolResult(call.id(), outcome.content()));
                if (!outcome.succeeded() && isNodeUnavailable(outcome.content())) {
                    throw new IllegalStateException("The node disconnected during the coding run; no further tool calls will be attempted.");
                }
            }
        }
        throw new IllegalStateException(
                "Coding run reached its maximum of " + MAX_MODEL_TURNS
                        + " model turns without a final answer. The model kept requesting tools.");
    }

    private ModelGateway.ModelAnswer completeWithRateLimitRetry(
            String runId,
            ActorContext actor,
            ModelGateway.ModelCompletionRequest request) {
        for (int retry = 0; ; retry++) {
            try {
                return modelGateway.complete(request);
            } catch (ModelRateLimitException ex) {
                if (retry >= MAX_RATE_LIMIT_RETRIES) {
                    throw new IllegalStateException(
                            "Model provider continued rate limiting after " + MAX_RATE_LIMIT_RETRIES + " retries.", ex);
                }
                Duration delay = rateLimitDelay(ex.retryAfter(), retry);
                events.publish(
                        runId,
                        RunEventType.MODEL_RATE_LIMITED,
                        "retry=" + (retry + 1) + ", delaySeconds=" + delay.toSeconds(),
                        actor);
                try {
                    retrySleeper.sleep(delay);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Coding run was interrupted while waiting for model rate limit recovery.", interrupted);
                }
            }
        }
    }

    private static Duration rateLimitDelay(Duration providerDelay, int retry) {
        if (providerDelay != null && !providerDelay.isNegative() && !providerDelay.isZero()) {
            return providerDelay.compareTo(MAX_RATE_LIMIT_DELAY) > 0 ? MAX_RATE_LIMIT_DELAY : providerDelay;
        }
        Duration exponential = INITIAL_RATE_LIMIT_DELAY.multipliedBy(retry + 1L);
        return exponential.compareTo(MAX_RATE_LIMIT_DELAY) > 0 ? MAX_RATE_LIMIT_DELAY : exponential;
    }

    private static List<ModelGateway.ModelToolCall> normalizeCalls(List<ModelGateway.ModelToolCall> calls) {
        if (calls == null || calls.isEmpty()) {
            return List.of();
        }
        List<ModelGateway.ModelToolCall> result = new ArrayList<>();
        for (ModelGateway.ModelToolCall call : calls) {
            String id = call.id() == null || call.id().isBlank() ? "call_" + UUID.randomUUID() : call.id();
            result.add(new ModelGateway.ModelToolCall(id, call.name(), call.arguments() == null ? Map.of() : call.arguments()));
        }
        return result;
    }

    private static boolean isNodeUnavailable(String toolResult) {
        return toolResult != null
                && (toolResult.contains("Node is not connected")
                        || toolResult.contains("Node is not online or enabled"));
    }

    @FunctionalInterface
    interface RetrySleeper {
        void sleep(Duration duration) throws InterruptedException;
    }
}
