package io.github.yourname.agentstudio.model;

import java.util.List;
import java.util.Map;

public interface ModelGateway {

    ModelAnswer complete(ModelCompletionRequest request);

    record ModelCompletionRequest(String modelProfileId, List<ModelMessage> messages, List<ModelTool> tools) {

        public ModelCompletionRequest(String modelProfileId, List<ModelMessage> messages) {
            this(modelProfileId, messages, List.of());
        }
    }

    record ModelMessage(
            String role,
            String content,
            String toolCallId,
            List<ModelToolCall> toolCalls) {

        public ModelMessage(String role, String content) {
            this(role, content, null, List.of());
        }

        public static ModelMessage assistantToolCalls(String content, List<ModelToolCall> toolCalls) {
            return new ModelMessage("assistant", content, null, toolCalls);
        }

        public static ModelMessage toolResult(String toolCallId, String content) {
            return new ModelMessage("tool", content, toolCallId, List.of());
        }
    }

    record ModelTool(String name, String description, Map<String, Object> inputSchema) {
    }

    record ModelToolCall(String id, String name, Map<String, Object> arguments) {
    }

    record ModelAnswer(
            String content,
            Integer promptTokens,
            Integer completionTokens,
            String rawModel,
            List<ModelToolCall> toolCalls,
            String finishReason) {

        public ModelAnswer(String content, Integer promptTokens, Integer completionTokens, String rawModel) {
            this(content, promptTokens, completionTokens, rawModel, List.of(), "stop");
        }
    }
}
