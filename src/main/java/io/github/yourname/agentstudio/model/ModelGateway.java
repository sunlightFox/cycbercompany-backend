package io.github.yourname.agentstudio.model;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public interface ModelGateway {

    ModelAnswer complete(ModelCompletionRequest request);

    /** Whether this gateway can emit text before the model turn completes. */
    default boolean supportsStreaming() {
        return false;
    }

    /**
     * Streams text deltas as the provider emits them, then returns the assembled answer.
     *
     * <p>The default keeps alternate gateways and test doubles source-compatible. Providers
     * that support streaming should override it; callers can still safely fall back to a
     * completed answer when they cannot.
     */
    default ModelAnswer stream(ModelCompletionRequest request, Consumer<String> onToken) {
        ModelAnswer answer = complete(request);
        if (answer.content() != null && !answer.content().isEmpty()) {
            onToken.accept(answer.content());
        }
        return answer;
    }

    record ModelCompletionRequest(
            String modelProfileId,
            List<ModelMessage> messages,
            List<ModelTool> tools,
            ToolChoice toolChoice) {

        public ModelCompletionRequest(String modelProfileId, List<ModelMessage> messages, List<ModelTool> tools) {
            this(modelProfileId, messages, tools, ToolChoice.AUTO);
        }

        public ModelCompletionRequest(String modelProfileId, List<ModelMessage> messages) {
            this(modelProfileId, messages, List.of(), ToolChoice.AUTO);
        }
    }

    /** Whether an OpenAI-compatible provider must emit a function call on this turn. */
    enum ToolChoice {
        AUTO,
        REQUIRED
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
