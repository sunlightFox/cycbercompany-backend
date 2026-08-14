package io.github.yourname.cycbercompany.nodeclient.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolExecutionResultTest {

    @Test
    void treatsNullResultAsAnEmptyMap() {
        ToolExecutionResult result = ToolExecutionResult.success(null);

        assertTrue(result.result().isEmpty());
    }

    @Test
    void preservesNullValuesInTheResultSnapshot() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("exitCode", null);
        source.put("stdout", "ok");

        ToolExecutionResult result = ToolExecutionResult.failure(source, "timed out");
        source.put("stdout", "changed");

        assertTrue(result.result().containsKey("exitCode"));
        assertEquals(null, result.result().get("exitCode"));
        assertEquals("ok", result.result().get("stdout"));
    }

    @Test
    void exposesAnImmutableResultMap() {
        ToolExecutionResult result = ToolExecutionResult.success(Map.of("stdout", "ok"));

        assertThrows(UnsupportedOperationException.class, () -> result.result().put("stderr", "nope"));
    }
}
