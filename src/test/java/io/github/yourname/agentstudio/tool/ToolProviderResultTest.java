package io.github.yourname.agentstudio.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolProviderResultTest {

    @Test
    void preservesTopLevelNullResultValuesAndOmitsNullKeys() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stdout", "ok");
        result.put("stderr", null);
        result.put(null, "ignored");

        ToolProviderResult providerResult =
                new ToolProviderResult("SUCCEEDED", true, result, null, null);

        assertThat(providerResult.result()).containsEntry("stdout", "ok");
        assertThat(providerResult.result()).containsKey("stderr");
        assertThat(providerResult.result().get("stderr")).isNull();
        assertThat(providerResult.result().keySet()).doesNotContainNull();
        assertThat(providerResult.errorMessage()).isEmpty();
    }
}
