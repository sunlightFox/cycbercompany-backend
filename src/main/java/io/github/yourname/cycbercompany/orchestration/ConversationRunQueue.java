package io.github.yourname.cycbercompany.orchestration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Starts each persisted run independently after its transaction commits.
 *
 * <p>The name is retained to avoid broad wiring churn. It deliberately does not serialize runs by
 * conversation: an unrelated or slow task must not block a later request from the same user.
 */
@Component
class ConversationRunQueue {

    private final Map<RunKey, Runnable> scheduled = new HashMap<>();

    synchronized int reserve(QueueKey key, String runId, Runnable worker) {
        scheduled.putIfAbsent(new RunKey(key, runId), worker);
        return 1;
    }

    void activate(QueueKey key) {
        List<Runnable> workers;
        synchronized (this) {
            workers = scheduled.entrySet().stream()
                    .filter(entry -> entry.getKey().queueKey().equals(key))
                    .map(Map.Entry::getValue)
                    .toList();
            scheduled.keySet().removeIf(runKey -> runKey.queueKey().equals(key));
        }
        workers.forEach(Runnable::run);
    }

    boolean resume(QueueKey key, String runId, Runnable worker) {
        worker.run();
        return true;
    }

    synchronized void complete(QueueKey key, String runId) {
        scheduled.remove(new RunKey(key, runId));
    }

    synchronized boolean cancelPending(QueueKey key, String runId) {
        return scheduled.remove(new RunKey(key, runId)) != null;
    }

    synchronized void cancel(QueueKey key, String runId) {
        scheduled.remove(new RunKey(key, runId));
    }

    synchronized Integer position(QueueKey key, String runId) {
        return scheduled.containsKey(new RunKey(key, runId)) ? 1 : null;
    }

    synchronized QueueSnapshot snapshot(QueueKey key) {
        return new QueueSnapshot(null, List.of());
    }

    record QueueKey(String tenantId, String conversationId) {
    }

    public record QueueEntry(String runId, int position) {
    }

    record QueueSnapshot(String activeRunId, List<QueueEntry> pending) {
    }

    private record RunKey(QueueKey queueKey, String runId) {
    }
}
