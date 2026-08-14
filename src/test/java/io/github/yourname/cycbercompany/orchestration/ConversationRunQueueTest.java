package io.github.yourname.cycbercompany.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConversationRunQueueTest {

    private static final ConversationRunQueue.QueueKey QUEUE =
            new ConversationRunQueue.QueueKey("tenant-a", "conversation-a");

    @Test
    void startsAllConversationRunsWithoutSerializingThem() {
        ConversationRunQueue queue = new ConversationRunQueue();
        List<String> started = new ArrayList<>();

        assertThat(queue.reserve(QUEUE, "run-1", () -> started.add("run-1"))).isEqualTo(1);
        assertThat(queue.reserve(QUEUE, "run-2", () -> started.add("run-2"))).isEqualTo(1);

        queue.activate(QUEUE);

        assertThat(started).containsExactlyInAnyOrder("run-1", "run-2");
        assertThat(queue.snapshot(QUEUE).activeRunId()).isNull();
        assertThat(queue.snapshot(QUEUE).pending()).isEmpty();
    }

    @Test
    void cancellationRemovesOnlyAnUnstartedRun() {
        ConversationRunQueue queue = new ConversationRunQueue();
        List<String> started = new ArrayList<>();
        queue.reserve(QUEUE, "run-1", () -> started.add("run-1"));
        queue.reserve(QUEUE, "run-2", () -> started.add("run-2"));

        assertThat(queue.cancelPending(QUEUE, "run-2")).isTrue();
        queue.activate(QUEUE);

        assertThat(started).containsExactly("run-1");
    }
}
