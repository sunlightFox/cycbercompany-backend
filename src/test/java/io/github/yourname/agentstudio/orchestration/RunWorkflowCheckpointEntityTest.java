package io.github.yourname.agentstudio.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class RunWorkflowCheckpointEntityTest {

    @Test
    void checkpointKeepsACompactSafeExecutionSummary() {
        Instant now = Instant.parse("2026-08-02T00:00:00Z");
        RunWorkflowCheckpointEntity checkpoint = new RunWorkflowCheckpointEntity(
                "run-1", "tenant", "Implement feature", "src", "[]", now);

        checkpoint.phase(RunWorkflowPhase.EXECUTING, now.plusSeconds(1));
        checkpoint.toolFinished("fs.read", true, null, now.plusSeconds(2));
        checkpoint.toolFinished("shell.run", false, "command failed", now.plusSeconds(3));

        assertThat(checkpoint.phase()).isEqualTo(RunWorkflowPhase.EXECUTING);
        assertThat(checkpoint.completedToolCalls()).isEqualTo(1);
        assertThat(checkpoint.failedToolCalls()).isEqualTo(1);
        assertThat(checkpoint.lastToolName()).isEqualTo("shell.run");
        assertThat(checkpoint.lastError()).isEqualTo("command failed");
    }

    @Test
    void longFailureIsBoundedBeforePersistence() {
        Instant now = Instant.now();
        RunWorkflowCheckpointEntity checkpoint = new RunWorkflowCheckpointEntity(
                "run-1", "tenant", "Implement feature", ".", "[]", now);
        String longError = "x".repeat(2_000);

        checkpoint.failure(longError, now.plusSeconds(1));

        assertThat(checkpoint.lastError()).hasSize(1_000);
        assertThat(checkpoint.phase()).isEqualTo(RunWorkflowPhase.FAILED);
    }
}
