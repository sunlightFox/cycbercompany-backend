package io.github.yourname.agentstudio.nodeclient.runtime;

import java.util.Map;

public record ToolExecutionResult(boolean success, Map<String, Object> result, String errorMessage) {

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
