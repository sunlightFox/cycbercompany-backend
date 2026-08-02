package io.github.yourname.agentstudio.orchestration;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RunRecoveryCoordinatorTest {

    @Test
    void startupRequeuesReadyTasksButMarksExpiredRunningTasksUnknown() {
        RunExecutionTaskService tasks = mock(RunExecutionTaskService.class);
        RunCommandService runs = mock(RunCommandService.class);
        RunExecutionTaskEntity ready = new RunExecutionTaskEntity(
                "run-ready", "tenant", "conversation", Instant.now());
        RunExecutionTaskEntity expired = new RunExecutionTaskEntity(
                "run-expired", "tenant", "conversation", Instant.now());
        expired.claim("old-lease", Instant.now().minusSeconds(600), java.time.Duration.ofSeconds(1));
        when(tasks.findRecoverable()).thenReturn(List.of(ready));
        when(tasks.findExpiredLeases()).thenReturn(List.of(expired));

        new RunRecoveryCoordinator(tasks, runs).recoverAtStartup();

        verify(runs).recoverPersistedRun("run-ready");
        verify(runs).markRunRecoveryUnknown(
                "run-expired", "Worker lease expired before the Run outcome could be safely recovered.");
    }
}
