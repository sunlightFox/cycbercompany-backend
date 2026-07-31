package io.github.yourname.agentstudio.orchestration;

import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.springframework.stereotype.Component;

/** Tracks the local worker currently responsible for each asynchronous run. */
@Component
class RunExecutionRegistry {

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, Future<?>> active = new ConcurrentHashMap<>();
    private final Set<String> cancelled = ConcurrentHashMap.newKeySet();

    void submit(String runId, Runnable task) {
        cancelled.remove(runId);
        Future<?> future = executor.submit(() -> {
            try {
                task.run();
            } finally {
                active.remove(runId);
            }
        });
        active.put(runId, future);
    }

    void cancel(String runId) {
        cancelled.add(runId);
        Future<?> future = active.remove(runId);
        if (future != null) {
            future.cancel(true);
        }
    }

    boolean isCancelled(String runId) {
        return cancelled.contains(runId);
    }

    @PreDestroy
    void close() {
        executor.shutdownNow();
    }
}
