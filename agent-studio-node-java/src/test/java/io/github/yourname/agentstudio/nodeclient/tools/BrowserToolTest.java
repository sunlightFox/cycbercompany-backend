package io.github.yourname.agentstudio.nodeclient.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BrowserToolTest {

    @Test
    void normalizesInteractiveElementsForModelConsumption() {
        String longText = "x".repeat(200);

        List<Map<String, Object>> elements = BrowserTool.normalizeInteractiveElements(List.of(
                Map.of(
                        "selector", "#task-title",
                        "tag", "input",
                        "type", "text",
                        "name", "title",
                        "placeholder", "Add a task",
                        "text", "",
                        "disabled", false),
                Map.of(
                        "selector", "button:nth-of-type(1)",
                        "tag", "button",
                        "text", longText,
                        "disabled", true),
                Map.of("tag", "button", "text", "missing selector")));

        assertEquals(2, elements.size());
        assertEquals("#task-title", elements.getFirst().get("selector"));
        assertEquals("title", elements.getFirst().get("name"));
        assertFalse((Boolean) elements.getFirst().get("disabled"));
        assertTrue(elements.get(1).get("text").toString().endsWith("..."));
        assertTrue((Boolean) elements.get(1).get("disabled"));
    }

    @Test
    void returnsNoElementsForUnexpectedEvaluationResults() {
        assertTrue(BrowserTool.normalizeInteractiveElements(Map.of("selector", "#not-a-list")).isEmpty());
        assertTrue(BrowserTool.normalizeInteractiveElements(null).isEmpty());
    }
}
