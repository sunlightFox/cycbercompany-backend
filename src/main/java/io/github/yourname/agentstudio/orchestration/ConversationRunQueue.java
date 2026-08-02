package io.github.yourname.agentstudio.orchestration;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Serializes model work within one tenant conversation while preserving parallelism between conversations.
 *
 * <p>Only scheduling metadata lives here. The run itself and its messages remain durable in the database.
 */
@Component
class ConversationRunQueue {

    private final Map<QueueKey, QueueState> queues = new HashMap<>();

    synchronized int reserve(QueueKey key, String runId, Runnable worker) {
        QueueState state = queues.computeIfAbsent(key, ignored -> new QueueState());
        // outbox 轮询和启动恢复都可能再次看到同一个 READY 任务。内存队列必须幂等，
        // 否则会保存两个 Runnable，导致同一个 Run 被两个本地 worker 依次执行。
        if (runId.equals(state.activeRunId)) {
            return 0;
        }
        int existingPosition = 1;
        for (QueuedRun pending : state.pending) {
            if (runId.equals(pending.runId())) {
                return existingPosition;
            }
            existingPosition++;
        }
        state.pending.addLast(new QueuedRun(runId, worker));
        return (state.activeRunId == null ? 1 : 2) + state.pending.size() - 1;
    }

    void activate(QueueKey key) {
        Runnable worker = null;
        synchronized (this) {
            QueueState state = queues.get(key);
            if (state == null || state.activeRunId != null || state.pending.isEmpty()) {
                return;
            }
            QueuedRun next = state.pending.removeFirst();
            state.activeRunId = next.runId();
            worker = next.worker();
        }
        worker.run();
    }

    boolean resume(QueueKey key, String runId, Runnable worker) {
        synchronized (this) {
            QueueState state = queues.get(key);
            if (state == null || !runId.equals(state.activeRunId)) {
                return false;
            }
        }
        worker.run();
        return true;
    }

    void complete(QueueKey key, String runId) {
        boolean activateNext = false;
        synchronized (this) {
            QueueState state = queues.get(key);
            if (state == null || !runId.equals(state.activeRunId)) {
                return;
            }
            state.activeRunId = null;
            if (state.pending.isEmpty()) {
                queues.remove(key);
            } else {
                activateNext = true;
            }
        }
        if (activateNext) {
            activate(key);
        }
    }

    synchronized boolean cancelPending(QueueKey key, String runId) {
        QueueState state = queues.get(key);
        if (state == null || runId.equals(state.activeRunId)) {
            return false;
        }
        boolean removed = state.pending.removeIf(item -> runId.equals(item.runId()));
        if (state.activeRunId == null && state.pending.isEmpty()) {
            queues.remove(key);
        }
        return removed;
    }

    synchronized Integer position(QueueKey key, String runId) {
        QueueState state = queues.get(key);
        if (state == null) {
            return null;
        }
        if (runId.equals(state.activeRunId)) {
            return 0;
        }
        int position = state.activeRunId == null ? 1 : 1;
        for (QueuedRun item : state.pending) {
            if (runId.equals(item.runId())) {
                return position;
            }
            position++;
        }
        return null;
    }

    synchronized QueueSnapshot snapshot(QueueKey key) {
        QueueState state = queues.get(key);
        if (state == null) {
            return new QueueSnapshot(null, List.of());
        }
        List<QueueEntry> pending = new ArrayList<>();
        int position = 1;
        for (QueuedRun item : state.pending) {
            pending.add(new QueueEntry(item.runId(), position++));
        }
        return new QueueSnapshot(state.activeRunId, List.copyOf(pending));
    }

    record QueueKey(String tenantId, String conversationId) {
    }

    public record QueueEntry(String runId, int position) {
    }

    record QueueSnapshot(String activeRunId, List<QueueEntry> pending) {
    }

    private static final class QueueState {
        private String activeRunId;
        private final Deque<QueuedRun> pending = new ArrayDeque<>();
    }

    private record QueuedRun(String runId, Runnable worker) {
    }
}
