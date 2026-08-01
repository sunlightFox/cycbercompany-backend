package io.github.yourname.agentstudio.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yourname.agentstudio.model.ModelGateway;
import io.github.yourname.agentstudio.model.ModelRateLimitException;
import io.github.yourname.agentstudio.security.ActorContext;
import io.github.yourname.agentstudio.tool.CodingWorkspaceScope;
import io.github.yourname.agentstudio.tool.ResolvedToolBinding;
import io.github.yourname.agentstudio.tool.ToolCleanupResult;
import io.github.yourname.agentstudio.tool.ToolInvocationRequest;
import io.github.yourname.agentstudio.tool.ToolProviderResult;
import io.github.yourname.agentstudio.tool.ToolRouter;
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
    // 以字符数近似上下文长度，避免长日志与截图文本在工具循环中无限累积。
    private static final int MAX_CONTEXT_CHARS = 96_000;
    private static final int RECENT_CONTEXT_CHARS = 42_000;
    private static final int MAX_RATE_LIMIT_RETRIES = 3;
    private static final Duration INITIAL_RATE_LIMIT_DELAY = Duration.ofSeconds(15);
    private static final Duration MAX_RATE_LIMIT_DELAY = Duration.ofSeconds(45);

    private final ModelGateway modelGateway;
    private final ToolRouter tools;
    private final RunEventPublisher events;
    private final RunExecutionRegistry executions;
    private final RetrySleeper retrySleeper;
    private final ObjectMapper objectMapper;

    @Autowired
    CodingAgentLoop(
            ModelGateway modelGateway,
            ToolRouter tools,
            RunEventPublisher events,
            RunExecutionRegistry executions,
            ObjectMapper objectMapper) {
        this(modelGateway, tools, events, executions, Thread::sleep, objectMapper);
    }

    CodingAgentLoop(
            ModelGateway modelGateway,
            ToolRouter tools,
            RunEventPublisher events,
            RetrySleeper retrySleeper) {
        this(modelGateway, tools, events, new RunExecutionRegistry(), retrySleeper, new ObjectMapper());
    }

    CodingAgentLoop(ModelGateway modelGateway, ToolRouter tools, RunEventPublisher events) {
        this(modelGateway, tools, events, new RunExecutionRegistry(), Thread::sleep, new ObjectMapper());
    }

    CodingAgentLoop(
            ModelGateway modelGateway,
            ToolRouter tools,
            RunEventPublisher events,
            RunExecutionRegistry executions,
            RetrySleeper retrySleeper) {
        this(modelGateway, tools, events, executions, retrySleeper, new ObjectMapper());
    }

    CodingAgentLoop(
            ModelGateway modelGateway,
            ToolRouter tools,
            RunEventPublisher events,
            RunExecutionRegistry executions,
            RetrySleeper retrySleeper,
            ObjectMapper objectMapper) {
        this.modelGateway = modelGateway;
        this.tools = tools;
        this.events = events;
        this.executions = executions;
        this.retrySleeper = retrySleeper;
        this.objectMapper = objectMapper;
    }

    String execute(
            String runId,
            String modelProfileId,
            List<ResolvedToolBinding> bindings,
            List<ModelGateway.ModelMessage> messages,
            ActorContext actor) {
        return execute(runId, modelProfileId, bindings, messages, actor, CodingWorkspaceScope.from(null));
    }

    String execute(
            String runId,
            String modelProfileId,
            List<ResolvedToolBinding> bindings,
            List<ModelGateway.ModelMessage> messages,
            ActorContext actor,
            CodingWorkspaceScope workspaceScope) {
        return execute(runId, modelProfileId, bindings, messages, actor, workspaceScope, true);
    }

    String resume(
            String runId,
            String modelProfileId,
            List<ResolvedToolBinding> bindings,
            List<ModelGateway.ModelMessage> messages,
            ActorContext actor) {
        return resume(runId, modelProfileId, bindings, messages, actor, CodingWorkspaceScope.from(null));
    }

    String resume(
            String runId,
            String modelProfileId,
            List<ResolvedToolBinding> bindings,
            List<ModelGateway.ModelMessage> messages,
            ActorContext actor,
            CodingWorkspaceScope workspaceScope) {
        return execute(runId, modelProfileId, bindings, messages, actor, workspaceScope, false);
    }

    private String execute(
            String runId,
            String modelProfileId,
            List<ResolvedToolBinding> bindings,
            List<ModelGateway.ModelMessage> messages,
            ActorContext actor,
            CodingWorkspaceScope workspaceScope,
            boolean requireFirstToolCall) {
        boolean waitingForApproval = false;
        try {
            ensureNotCancelled(runId);
            List<ResolvedToolBinding> available = bindings == null ? List.of() : List.copyOf(bindings);
            if (available.isEmpty()) {
                throw new IllegalArgumentException("This run has no effective tools after applying Agent and Run policies.");
            }
            Map<String, ResolvedToolBinding> byModelName = new HashMap<>();
            for (ResolvedToolBinding tool : available) {
                byModelName.put(tool.modelName(), tool);
            }
            List<ModelGateway.ModelTool> modelTools = available.stream()
                    .map(tool -> new ModelGateway.ModelTool(
                            tool.modelName(),
                            tool.description() + (tool.requiresApproval()
                                    ? " This call requires human approval before execution."
                                    : ""),
                            tool.inputSchema()))
                    .toList();
            // A resumed run already has a completed (or explicitly rejected) tool call in its
            // persisted context, so a final text response is valid on its first resumed turn.
            int executedCalls = requireFirstToolCall ? 0 : 1;

            for (int turn = 1; turn <= MAX_MODEL_TURNS; turn++) {
                ensureNotCancelled(runId);
                compactContextIfNeeded(messages);
                var answer = completeWithRateLimitRetry(
                    runId,
                    actor,
                    new ModelGateway.ModelCompletionRequest(
                            modelProfileId,
                            messages,
                            modelTools,
                            turn == 1 && requireFirstToolCall ? ModelGateway.ToolChoice.REQUIRED : ModelGateway.ToolChoice.AUTO));
                ensureNotCancelled(runId);
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
                    ensureNotCancelled(runId);
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
                    ResolvedToolBinding tool = byModelName.get(call.name());
                    if (tool == null) {
                        String result = "{\"status\":\"FAILED\",\"error\":\"Tool is not available in this run.\"}";
                        events.publish(runId, RunEventType.TOOL_CALL_FAILED, "Unknown tool requested: " + call.name(), actor);
                        messages.add(ModelGateway.ModelMessage.toolResult(call.id(), result));
                        continue;
                    }
                    events.publish(runId, RunEventType.TOOL_CALL_REQUESTED, "tool=" + tool.logicalName(), actor);
                    events.publish(runId, RunEventType.TOOL_CALL_STARTED, "tool=" + tool.logicalName(), actor);
                    ToolProviderResult outcome = tools.invoke(new ToolInvocationRequest(
                            runId,
                            call.id(),
                            tool,
                            call.arguments(),
                            timeoutSeconds(call.arguments()),
                            workspaceScope,
                            actor));
                    if (outcome.requiresApproval()) {
                        for (int deferred = callIndex + 1; deferred < calls.size(); deferred++) {
                            messages.add(ModelGateway.ModelMessage.toolResult(
                                    calls.get(deferred).id(),
                                    "{\"status\":\"DEFERRED\",\"error\":\"Run paused while another tool call awaits approval. Request this tool again after approval.\"}"));
                        }
                        events.publish(
                                runId,
                                RunEventType.TOOL_APPROVAL_REQUIRED,
                                "tool=" + tool.logicalName() + ", approvalId=" + outcome.approvalId(),
                                actor);
                        waitingForApproval = true;
                        throw new CodingApprovalRequiredException(outcome.approvalId(), call.id(), messages);
                    }
                    events.publish(
                            runId,
                            outcome.succeeded() ? RunEventType.TOOL_CALL_COMPLETED : RunEventType.TOOL_CALL_FAILED,
                            "tool=" + tool.logicalName(),
                            actor);
                    String outcomeContent = serializeToolResult(tool, outcome);
                    messages.add(ModelGateway.ModelMessage.toolResult(call.id(), outcomeContent));
                    if (!outcome.succeeded() && isNodeUnavailable(outcomeContent)) {
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

    void cleanupManagedProcesses(String runId, ActorContext actor) {
        try {
            for (ToolCleanupResult result : tools.cleanup(runId, actor)) {
                String detail = "tool=" + result.toolName() + " cleanup";
                events.publish(runId, RunEventType.TOOL_CALL_STARTED, detail, actor);
                events.publish(
                        runId,
                        result.succeeded() ? RunEventType.TOOL_CALL_COMPLETED : RunEventType.TOOL_CALL_FAILED,
                        detail + (result.errorMessage() == null ? "" : ", error=" + result.errorMessage()),
                        actor);
            }
        } catch (Exception ignored) {
            // Cleanup failures must not hide the model's final answer or original execution failure.
        }
    }

    /**
     * Provider 保持结构化返回，只有在送回模型前才统一序列化。
     * 这样 Node、MCP 和后端工具共享同一种结果合同，也只需在一个位置控制上下文大小。
     */
    private String serializeToolResult(ResolvedToolBinding binding, ToolProviderResult outcome) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("status", outcome.status());
        payload.put("tool", binding.logicalName());
        payload.put("provider", binding.providerId());
        payload.put("result", outcome.result());
        payload.put("error", outcome.errorMessage());
        try {
            String json = objectMapper.writeValueAsString(payload);
            return json.length() <= 12_000 ? json : json.substring(0, 12_000) + "... [tool result truncated]";
        } catch (Exception ex) {
            return "{\"status\":\"FAILED\",\"error\":\"Unable to serialize tool result\"}";
        }
    }

    private static Integer timeoutSeconds(Map<String, Object> arguments) {
        Object value = arguments == null ? null : arguments.get("timeoutSeconds");
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private ModelGateway.ModelAnswer completeWithRateLimitRetry(
            String runId,
            ActorContext actor,
            ModelGateway.ModelCompletionRequest request) {
        for (int retry = 0; ; retry++) {
            try {
                ensureNotCancelled(runId);
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

    private void ensureNotCancelled(String runId) {
        if (executions.isCancelled(runId)) {
            throw new CodingRunCancelledException(runId);
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

    /**
     * 在下一次模型调用前压缩过长的工具历史。
     *
     * <p>保留开头的系统指令与用户需求，并从尾部保留最近一段完整消息；被移除的旧工具输出
     * 不应再被当作文件当前状态。因此插入明确提示，要求模型重新用受限工具确认事实。
     */
    static void compactContextIfNeeded(List<ModelGateway.ModelMessage> messages) {
        int size = messages.stream().mapToInt(CodingAgentLoop::messageChars).sum();
        if (size <= MAX_CONTEXT_CHARS || messages.size() <= 4) {
            return;
        }
        List<ModelGateway.ModelMessage> prefix = new ArrayList<>();
        int index = 0;
        // 初始的 system 和 user 指令定义任务目标，不能因为日志过长被删除。
        while (index < messages.size() && prefix.size() < 2) {
            ModelGateway.ModelMessage message = messages.get(index++);
            if ("system".equals(message.role()) || "user".equals(message.role())) {
                prefix.add(message);
            }
        }
        List<ModelGateway.ModelMessage> tail = new ArrayList<>();
        int retained = 0;
        for (int position = messages.size() - 1; position >= index; position--) {
            ModelGateway.ModelMessage message = messages.get(position);
            if (retained + messageChars(message) > RECENT_CONTEXT_CHARS && !tail.isEmpty()) {
                break;
            }
            tail.addFirst(message);
            retained += messageChars(message);
        }
        int removed = messages.size() - prefix.size() - tail.size();
        messages.clear();
        messages.addAll(prefix);
        messages.add(new ModelGateway.ModelMessage(
                "system",
                "Earlier tool history was compacted (" + Math.max(0, removed)
                        + " messages). Do not assume old outputs describe the current workspace. "
                        + "Use project.map, project.inspect, git.diff, fs.search, or fs.read to re-check relevant facts before editing."));
        messages.addAll(tail);
    }

    private static int messageChars(ModelGateway.ModelMessage message) {
        int content = message.content() == null ? 0 : message.content().length();
        int toolCalls = message.toolCalls() == null ? 0 : message.toolCalls().stream()
                .mapToInt(call -> (call.name() == null ? 0 : call.name().length()) + call.arguments().toString().length())
                .sum();
        return content + toolCalls;
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

    private static final class CodingRunCancelledException extends RuntimeException {
        private CodingRunCancelledException(String runId) {
            super("Coding run was cancelled: " + runId);
        }
    }
}
