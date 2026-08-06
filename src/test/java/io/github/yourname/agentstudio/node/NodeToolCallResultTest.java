package io.github.yourname.agentstudio.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;

class NodeToolCallResultTest {

    @Test
    void preservesNullResultValuesAndOmitsNullKeys() {
        var result = new LinkedHashMap<String, Object>();
        result.put("exitCode", null);
        result.put("stdout", "timed out");
        result.put(null, "ignored");

        var callResult = new NodeToolCallResult(
                "inv-1", "node-1", "system.shell.run", "FAILED", result, "timed out");

        assertThat(callResult.result()).containsEntry("stdout", "timed out");
        assertThat(callResult.result()).containsKey("exitCode");
        assertThat(callResult.result().get("exitCode")).isNull();
        assertThat(callResult.result().keySet()).doesNotContainNull();
        assertThatThrownBy(() -> callResult.result().put("stderr", "nope"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
