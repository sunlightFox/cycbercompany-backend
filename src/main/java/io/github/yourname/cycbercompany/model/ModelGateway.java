package io.github.yourname.cycbercompany.model;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 模型调用抽象。
 *
 * <p>编排层只依赖这个接口，不关心底层 HTTP JSON 长什么样。这样可以替换 Provider，
 * 也方便测试时注入不访问网络的假网关。
 */
public interface ModelGateway {

    /** 执行一次非流式模型调用，并返回完整答案或工具调用。 */
    ModelAnswer complete(ModelCompletionRequest request);

    /** 判断当前网关能否在模型完成前持续产生文本增量。 */
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

    /** 控制模型是否必须在本轮产生工具调用。 */
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

    /** 模型在一次响应中提出的工具调用请求。 */
    record ModelToolCall(String id, String name, Map<String, Object> arguments) {
    }

    /** 网关归一化后的模型响应，工具调用也作为结构化数据返回。 */
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
