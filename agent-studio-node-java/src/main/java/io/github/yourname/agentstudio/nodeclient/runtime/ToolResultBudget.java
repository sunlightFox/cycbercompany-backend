package io.github.yourname.agentstudio.nodeclient.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yourname.agentstudio.nodeclient.protocol.NodeProtocolLimits;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 在节点发送 tool.result 前执行最后一道统一结果预算。
 *
 * <p>各工具仍应尽早限制自己的 stdout、stderr 和正文；本类用于防止某个新工具忘记限制，
 * 导致一条结果撑爆 WebSocket。发生裁剪时保留结构化 {@code truncated=true}，模型和页面
 * 都能明确知道看到的不是完整结果。
 */
public final class ToolResultBudget {

    private static final int MAX_DEPTH = 12;
    private static final int MAX_COLLECTION_ITEMS = 256;

    private final ObjectMapper objectMapper;

    public ToolResultBudget(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ToolExecutionResult apply(ToolExecutionResult source) {
        TruncationState state = new TruncationState();
        Map<String, Object> normalized = normalizeMap(source.result(), 0, state);
        if (state.truncated) {
            normalized.put("truncated", true);
        }

        int serializedBytes = serializedBytes(normalized);
        if (serializedBytes > NodeProtocolLimits.MAX_TOOL_RESULT_BYTES) {
            String preview = truncateUtf8(serialize(normalized), NodeProtocolLimits.MAX_RESULT_TEXT_BYTES);
            normalized = new LinkedHashMap<>();
            normalized.put("truncated", true);
            normalized.put("truncationReason", "tool result exceeded the structured result budget");
            normalized.put("originalSizeBytes", serializedBytes);
            normalized.put("preview", preview);
        }

        String error = truncateUtf8(source.errorMessage(), NodeProtocolLimits.MAX_ERROR_MESSAGE_BYTES);
        if (source.errorMessage() != null && !source.errorMessage().equals(error)) {
            normalized.put("truncated", true);
            normalized.put("errorTruncated", true);
        }
        // Shell 超时等合法结果会包含 exitCode=null，Map.copyOf 不接受 null 值。
        return new ToolExecutionResult(
                source.success(),
                Collections.unmodifiableMap(new LinkedHashMap<>(normalized)),
                error);
    }

    private Map<String, Object> normalizeMap(Map<String, Object> source, int depth, TruncationState state) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (source == null) {
            return result;
        }
        int count = 0;
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (count++ >= MAX_COLLECTION_ITEMS) {
                state.truncated = true;
                break;
            }
            result.put(entry.getKey(), normalize(entry.getValue(), depth + 1, state));
        }
        return result;
    }

    private Object normalize(Object value, int depth, TruncationState state) {
        if (value == null || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (depth > MAX_DEPTH) {
            state.truncated = true;
            return "[nested value omitted]";
        }
        if (value instanceof CharSequence text) {
            String normalized = truncateUtf8(text.toString(), NodeProtocolLimits.MAX_RESULT_TEXT_BYTES);
            if (!normalized.equals(text.toString())) {
                state.truncated = true;
            }
            return normalized;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            int count = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (count++ >= MAX_COLLECTION_ITEMS) {
                    state.truncated = true;
                    break;
                }
                normalized.put(String.valueOf(entry.getKey()), normalize(entry.getValue(), depth + 1, state));
            }
            return normalized;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> normalized = new ArrayList<>();
            for (Object item : iterable) {
                if (normalized.size() >= MAX_COLLECTION_ITEMS) {
                    state.truncated = true;
                    break;
                }
                normalized.add(normalize(item, depth + 1, state));
            }
            return normalized;
        }
        return normalize(value.toString(), depth + 1, state);
    }

    private int serializedBytes(Object value) {
        return serialize(value).getBytes(StandardCharsets.UTF_8).length;
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "{\"truncated\":true,\"truncationReason\":\"result serialization failed\"}";
        }
    }

    static String truncateUtf8(String value, int maxBytes) {
        if (value == null) {
            return null;
        }
        if (value.getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
            return value;
        }
        int bytes = 0;
        int end = 0;
        while (end < value.length()) {
            int codePoint = Character.codePointAt(value, end);
            int codePointBytes = codePoint <= 0x7f ? 1 : codePoint <= 0x7ff ? 2 : codePoint <= 0xffff ? 3 : 4;
            if (bytes + codePointBytes > maxBytes) {
                break;
            }
            bytes += codePointBytes;
            end += Character.charCount(codePoint);
        }
        return value.substring(0, end);
    }

    private static final class TruncationState {
        private boolean truncated;
    }
}
