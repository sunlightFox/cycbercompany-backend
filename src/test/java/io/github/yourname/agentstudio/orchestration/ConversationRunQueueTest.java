package io.github.yourname.agentstudio.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConversationRunQueueTest {

    private static final ConversationRunQueue.QueueKey QUEUE =
            new ConversationRunQueue.QueueKey("tenant-a", "conversation-a");

    @Test
    void runsOneConversationInFirstInFirstOutOrder() {
        ConversationRunQueue queue = new ConversationRunQueue();
        List<String> started = new ArrayList<>();

        assertThat(queue.reserve(QUEUE, "run-1", () -> started.add("run-1"))).isEqualTo(1);
        assertThat(queue.reserve(QUEUE, "run-2", () -> started.add("run-2"))).isEqualTo(2);
        assertThat(queue.reserve(QUEUE, "run-3", () -> started.add("run-3"))).isEqualTo(3);

        queue.activate(QUEUE);
        assertThat(started).containsExactly("run-1");
        assertThat(queue.position(QUEUE, "run-1")).isZero();
        assertThat(queue.position(QUEUE, "run-2")).isEqualTo(1);
        assertThat(queue.position(QUEUE, "run-3")).isEqualTo(2);

        queue.complete(QUEUE, "run-1");
        assertThat(started).containsExactly("run-1", "run-2");
        queue.complete(QUEUE, "run-2");
        assertThat(started).containsExactly("run-1", "run-2", "run-3");
    }

    @Test
    void activeRunHoldsItsSlotUntilApprovalContinuationCompletes() {
        ConversationRunQueue queue = new ConversationRunQueue();
        List<String> started = new ArrayList<>();

        queue.reserve(QUEUE, "run-1", () -> started.add("initial"));
        queue.reserve(QUEUE, "run-2", () -> started.add("run-2"));
        queue.activate(QUEUE);

        assertThat(queue.resume(QUEUE, "run-1", () -> started.add("resumed"))).isTrue();
        assertThat(started).containsExactly("initial", "resumed");
        assertThat(queue.position(QUEUE, "run-2")).isEqualTo(1);

        queue.complete(QUEUE, "run-1");
        assertThat(started).containsExactly("initial", "resumed", "run-2");
    }

    @Test
    void cancellingAPendingRunRemovesItWithoutStoppingTheActiveRun() {
        ConversationRunQueue queue = new ConversationRunQueue();
        List<String> started = new ArrayList<>();

        queue.reserve(QUEUE, "run-1", () -> started.add("run-1"));
        queue.reserve(QUEUE, "run-2", () -> started.add("run-2"));
        queue.activate(QUEUE);

        assertThat(queue.cancelPending(QUEUE, "run-2")).isTrue();
        assertThat(started).containsExactly("run-1");
        assertThat(queue.position(QUEUE, "run-2")).isNull();

        queue.complete(QUEUE, "run-1");
        assertThat(started).containsExactly("run-1");
    }
}
