package io.github.yourname.agentstudio.orchestration;

import io.github.yourname.agentstudio.model.ModelGateway;
import io.github.yourname.agentstudio.security.ActorContext;
import io.github.yourname.agentstudio.tool.CodingToolAdapter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** A bounded native-tool loop for repository editing and verification work. */
@Service
class CodingAgentLoop {

    private static final int MAX_MODEL_TURNS = 16;
    private static final int MAX_TOOL_CALLS = 32;

    private final ModelGateway modelGateway;
    private final CodingToolAdapter tools;
    private final RunEventPublisher events;

    CodingAgentLoop(ModelGateway modelGateway, CodingToolAdapter tools, RunEventPublisher events) {
        this.modelGateway = modelGateway;
        this.tools = tools;
        this.events = events;
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
            var answer = modelGateway.complete(new ModelGateway.ModelCompletionRequest(modelProfileId, messages, modelTools));
            List<ModelGateway.ModelToolCall> calls = normalizeCalls(answer.toolCalls());
            if (calls.isEmpty()) {
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
            }
        }
        throw new IllegalStateException("Coding run reached its maximum of " + MAX_MODEL_TURNS + " model turns.");
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
}
