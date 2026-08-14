package io.github.yourname.cycbercompany.orchestration;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 验证持久化 outbox 只在本地调度确认成功后才被标记为已处理。 */
class RunExecutionOutboxDispatcherTest {

    @Test
    void confirmsMessageOnlyAfterThePersistedRunWasHandedToTheQueue() {
        RunExecutionOutboxService outbox = mock(RunExecutionOutboxService.class);
        RunCommandService runs = mock(RunCommandService.class);
        RunExecutionOutboxService.ClaimedMessage message = new RunExecutionOutboxService.ClaimedMessage(
                "outbox-1", "run-1", "lease-1");
        when(outbox.claimPending(32)).thenReturn(List.of(message));
        RunExecutionOutboxDispatcher dispatcher = new RunExecutionOutboxDispatcher(outbox, runs);

        dispatcher.dispatchPending();

        verify(runs).recoverPersistedRun("run-1");
        verify(outbox).markProcessed("outbox-1", "lease-1");
    }

    @Test
    void returnsFailedDispatchToTheOutboxForBackoffRetry() {
        RunExecutionOutboxService outbox = mock(RunExecutionOutboxService.class);
        RunCommandService runs = mock(RunCommandService.class);
        RunExecutionOutboxService.ClaimedMessage message = new RunExecutionOutboxService.ClaimedMessage(
                "outbox-2", "run-2", "lease-2");
        when(outbox.claimPending(32)).thenReturn(List.of(message));
        IllegalStateException failure = new IllegalStateException("queue unavailable");
        doThrow(failure).when(runs).recoverPersistedRun("run-2");
        RunExecutionOutboxDispatcher dispatcher = new RunExecutionOutboxDispatcher(outbox, runs);

        dispatcher.dispatchPending();

        verify(outbox).retry("outbox-2", "lease-2", failure);
    }
}
