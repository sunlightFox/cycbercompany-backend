package io.github.yourname.cycbercompany.orchestration;

import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Tracks the local worker currently responsible for each asynchronous run. */
@Component
class RunExecutionRegistry {

    private static final Logger log = LoggerFactory.getLogger(RunExecutionRegistry.class);
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, Future<?>> active = new ConcurrentHashMap<>();
    private final Set<String> cancelled = ConcurrentHashMap.newKeySet();

    void submit(String runId, Runnable task) {
        cancelled.remove(runId);
        log.info("Submitting local worker for run {}.", runId);
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
