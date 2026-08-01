package io.github.yourname.agentstudio.nodeclient.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yourname.agentstudio.nodeclient.protocol.NodeProtocolLimits;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolResultBudgetTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ToolResultBudget budget = new ToolResultBudget(objectMapper);

    @Test
    void truncatesLargeTextAndErrorFieldsWithoutLosingStructuredStatus() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stdout", "你".repeat(30_000));
        result.put("stderr", "x".repeat(80_000));
        result.put("exitCode", null);
        ToolExecutionResult bounded = budget.apply(ToolExecutionResult.failure(result, "e".repeat(20_000)));

        assertEquals(false, bounded.success());
        assertEquals(true, bounded.result().get("truncated"));
        assertEquals(true, bounded.result().get("errorTruncated"));
        assertTrue(bytes((String) bounded.result().get("stdout")) <= NodeProtocolLimits.MAX_RESULT_TEXT_BYTES);
        assertTrue(bytes((String) bounded.result().get("stderr")) <= NodeProtocolLimits.MAX_RESULT_TEXT_BYTES);
        assertTrue(bytes(bounded.errorMessage()) <= NodeProtocolLimits.MAX_ERROR_MESSAGE_BYTES);
        assertTrue(bytes(objectMapper.writeValueAsString(bounded.result())) <= NodeProtocolLimits.MAX_TOOL_RESULT_BYTES);
    }

    @Test
    void fallsBackToABoundedPreviewWhenManyFieldsStillExceedTheTotalBudget() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < 20; index++) {
            result.put("field" + index, "x".repeat(NodeProtocolLimits.MAX_RESULT_TEXT_BYTES));
        }

        ToolExecutionResult bounded = budget.apply(ToolExecutionResult.success(result));

        assertEquals(true, bounded.result().get("truncated"));
        assertTrue(bounded.result().containsKey("preview"));
        assertTrue(bytes(objectMapper.writeValueAsString(bounded.result())) <= NodeProtocolLimits.MAX_TOOL_RESULT_BYTES);
    }

    @Test
    void neverSplitsASupplementaryUnicodeCodePoint() {
        String value = "a😀b";

        assertEquals("a", ToolResultBudget.truncateUtf8(value, 4));
        assertEquals("a😀", ToolResultBudget.truncateUtf8(value, 5));
    }

    private static int bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }
}
