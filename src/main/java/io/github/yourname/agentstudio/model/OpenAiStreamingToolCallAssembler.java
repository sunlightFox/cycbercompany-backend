package io.github.yourname.agentstudio.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;

/**
 * 将 OpenAI 兼容 SSE 中被拆分的 {@code delta.tool_calls} 重新组装为完整工具调用。
 *
 * <p>一个函数名、调用 ID 和 JSON 参数常常分布在多个 SSE 事件里。例如首个事件只带
 * {@code {"path":"}，下一个事件才带 {@code README.md"}}。若流结束时直接丢弃这些
 * 分片，编码 Agent 会误以为模型没有调用工具并提前结束。因此本类按供应商给出的
 * {@code index} 保存最小状态，只有在流结束后才校验完整性和解析 JSON 参数。
 */
final class OpenAiStreamingToolCallAssembler {

    private final TreeMap<Integer, PartialToolCall> calls = new TreeMap<>();

    /** 接收一个 delta 中的 tool_calls 数组；数组内每个元素都可能是不完整的。 */
    void accept(JsonNode toolCalls) {
        if (toolCalls == null || toolCalls.isNull()) {
            return;
        }
        if (!toolCalls.isArray()) {
            throw new ModelGatewayException("Model provider returned invalid streamed tool calls.");
        }
        for (int position = 0; position < toolCalls.size(); position++) {
            JsonNode fragment = toolCalls.get(position);
            if (fragment == null || !fragment.isObject()) {
                throw new ModelGatewayException("Model provider returned invalid streamed tool call fragment.");
            }
            int index = toolIndex(fragment, position);
            PartialToolCall call = calls.computeIfAbsent(index, ignored -> new PartialToolCall());
            appendIfPresent(call.id, fragment.get("id"));

            JsonNode function = fragment.get("function");
            if (function != null && !function.isNull()) {
                if (!function.isObject()) {
                    throw new ModelGatewayException("Model provider returned invalid streamed tool function.");
                }
                appendIfPresent(call.name, function.get("name"));
                appendIfPresent(call.arguments, function.get("arguments"));
            }
        }
    }

    /**
     * 仅在 SSE 流完成后调用。此时参数必须是完整 JSON，不能把供应商的半截参数传给节点。
     */
    ArrayList<ModelGateway.ModelToolCall> complete(ObjectMapper objectMapper) {
        ArrayList<ModelGateway.ModelToolCall> result = new ArrayList<>();
        for (PartialToolCall partial : calls.values()) {
            String id = partial.id.toString();
            String name = partial.name.toString();
            if (id.isBlank() || name.isBlank()) {
                throw new ModelGatewayException("Model provider returned an incomplete streamed tool call.");
            }
            try {
                String arguments = partial.arguments.toString();
                Map<String, Object> parsed = arguments.isBlank()
                        ? Map.of()
                        : objectMapper.readValue(arguments, new TypeReference<Map<String, Object>>() {
                        });
                result.add(new ModelGateway.ModelToolCall(id, name, parsed));
            } catch (Exception ex) {
                throw new ModelGatewayException(
                        "Model provider returned invalid streamed tool arguments: " + ex.getMessage(), ex);
            }
        }
        return result;
    }

    private static int toolIndex(JsonNode fragment, int fallback) {
        JsonNode index = fragment.get("index");
        if (index == null || index.isNull()) {
            return fallback;
        }
        if (!index.canConvertToInt() || index.asInt() < 0) {
            throw new ModelGatewayException("Model provider returned an invalid streamed tool call index.");
        }
        return index.asInt();
    }

    private static void appendIfPresent(StringBuilder target, JsonNode value) {
        if (value != null && !value.isNull()) {
            target.append(value.asText());
        }
    }

    private static final class PartialToolCall {
        private final StringBuilder id = new StringBuilder();
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();
    }
}
