package io.github.yourname.cycbercompany.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class RunEventEntityTest {

    @Test
    void exposesThePersistedEventNameAsItsEnumValue() {
        var event = new RunEventEntity("tenant", "run", 1, RunEventType.TOOL_CALL_COMPLETED, "tool=fs.read", Instant.now());

        assertThat(event.type()).isEqualTo(RunEventType.TOOL_CALL_COMPLETED);
    }
}
