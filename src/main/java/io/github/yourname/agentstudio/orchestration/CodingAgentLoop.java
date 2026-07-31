package io.github.yourname.agentstudio.orchestration;

import io.github.yourname.agentstudio.model.ModelGateway;
import io.github.yourname.agentstudio.model.ModelRateLimitException;
import io.github.yourname.agentstudio.security.ActorContext;
import io.github.yourname.agentstudio.tool.CodingToolAdapter;
import io.github.yourname.agentstudio.tool.CodingWorkspaceScope;
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

    private static final int MAX_MODEL_TURNS = 24;
    private static final int MAX_TOOL_CALLS = 48;
    private static final int TOOL_BUDGET_WARNING = 36;
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
        return execute(runId, modelProfileId, nodeId, messages, actor, CodingWorkspaceScope.from(null));
    }

    String execute(
            String runId,
            String modelProfileId,
            String nodeId,
            List<ModelGateway.ModelMessage> messages,
            ActorContext actor,
            CodingWorkspaceScope workspaceScope) {
        return execute(runId, modelProfileId, nodeId, messages, actor, workspaceScope, true);
    }

    String resume(
            String runId,
            String modelProfileId,
            String nodeId,
            List<ModelGateway.ModelMessage> messages,
            ActorContext actor) {
        return resume(runId, modelProfileId, nodeId, messages, actor, CodingWorkspaceScope.from(null));
    }

    String resume(
            String runId,
            String modelProfileId,
            String nodeId,
            List<ModelGateway.ModelMessage> messages,
            ActorContext actor,
            CodingWorkspaceScope workspaceScope) {
        return execute(runId, modelProfileId, nodeId, messages, actor, workspaceScope, false);
    }

    private String execute(
            String runId,
            String modelProfileId,
            String nodeId,
            List<ModelGateway.ModelMessage> messages,
            ActorContext actor,
            CodingWorkspaceScope workspaceScope,
            boolean requireFirstToolCall) {
        boolean waitingForApproval = false;
        try {
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
            // A resumed run already has a completed (or explicitly rejected) tool call in its
            // persisted context, so a final text response is valid on its first resumed turn.
            int executedCalls = requireFirstToolCall ? 0 : 1;

            for (int turn = 1; turn <= MAX_MODEL_TURNS; turn++) {
                var answer = completeWithRateLimitRetry(
                    runId,
                    actor,
                    new ModelGateway.ModelCompletionRequest(
                            modelProfileId,
                            messages,
                            modelTools,
                            turn == 1 && requireFirstToolCall ? ModelGateway.ToolChoice.REQUIRED : ModelGateway.ToolChoice.AUTO));
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
                for (int callIndex = 0; callIndex < calls.size(); callIndex++) {
                    ModelGateway.ModelToolCall call = calls.get(callIndex);
                    if (++executedCalls > MAX_TOOL_CALLS) {
                        throw new IllegalStateException("Coding run reached its maximum of " + MAX_TOOL_CALLS + " tool calls.");
                    }
                    if (executedCalls == TOOL_BUDGET_WARNING) {
                        events.publish(
                                runId,
                                RunEventType.TOOL_BUDGET_WARNING,
                                "toolCalls=" + executedCalls + ", max=" + MAX_TOOL_CALLS
                                        + ". Focus on the requested files and verification.",
                                actor);
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
                    CodingToolAdapter.ToolExecution outcome = tools.execute(runId, tool, call, actor, workspaceScope);
                    if (outcome.requiresApproval()) {
                        for (int deferred = callIndex + 1; deferred < calls.size(); deferred++) {
                            messages.add(ModelGateway.ModelMessage.toolResult(
                                    calls.get(deferred).id(),
                                    "{\"status\":\"DEFERRED\",\"error\":\"Run paused while another tool call awaits approval. Request this tool again after approval.\"}"));
                        }
                        events.publish(
                                runId,
                                RunEventType.TOOL_APPROVAL_REQUIRED,
                                "tool=" + tool.nodeToolName() + ", approvalId=" + outcome.approvalId(),
                                actor);
                        waitingForApproval = true;
                        throw new CodingApprovalRequiredException(outcome.approvalId(), call.id(), messages);
                    }
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
        } finally {
            if (!waitingForApproval) {
                cleanupManagedProcesses(runId, actor);
            }
        }
    }

    private void cleanupManagedProcesses(String runId, ActorContext actor) {
        try {
            for (CodingToolAdapter.CleanupResult result : tools.cleanupRun(runId, actor)) {
                events.publish(runId, RunEventType.TOOL_CALL_STARTED, "tool=process.stop cleanup", actor);
                events.publish(
                        runId,
                        result.succeeded() ? RunEventType.TOOL_CALL_COMPLETED : RunEventType.TOOL_CALL_FAILED,
                        "tool=process.stop cleanup" + (result.errorMessage() == null ? "" : ", error=" + result.errorMessage()),
                        actor);
            }
        } catch (Exception ignored) {
            // Cleanup failures must not hide the model's final answer or original execution failure.
        }
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
