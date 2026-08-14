package io.github.yourname.cycbercompany.nodeclient.runtime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ToolExecutionResult(boolean success, Map<String, Object> result, String errorMessage) {

    public ToolExecutionResult {
        result = result == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(result));
    }

    public static ToolExecutionResult success(Map<String, Object> result) {
        return new ToolExecutionResult(true, result, null);
    }

    public static ToolExecutionResult failure(String errorMessage) {
        return new ToolExecutionResult(false, Map.of(), errorMessage);
    }

    public static ToolExecutionResult failure(Map<String, Object> result, String errorMessage) {
        return new ToolExecutionResult(false, result, errorMessage);
    }
}
