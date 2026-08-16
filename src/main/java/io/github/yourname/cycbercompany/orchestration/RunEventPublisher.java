package io.github.yourname.cycbercompany.orchestration;

import io.github.yourname.cycbercompany.security.ActorContext;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Persists Run events before publishing them to resumable SSE subscribers. */
@Component
public class RunEventPublisher {

    private final RunEventRepository repository;
    private final AgentRunRepository runs;
    private final TransactionTemplate transactions;
    private final Map<String, List<Subscriber>> emitters = new ConcurrentHashMap<>();
    private final Map<String, RunLock> runLocks = new ConcurrentHashMap<>();

    public RunEventPublisher(
            RunEventRepository repository,
            AgentRunRepository runs,
            org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.runs = runs;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Transactional
    public RunEventView publish(String runId, RunEventType type, String payload, ActorContext actor) {
        RunLock lock = acquireLock(runId);
        try {
            synchronized (lock.monitor) {
            AgentRunEntity run = runs.findWithLockByIdAndTenantId(runId, actor.tenantId())
                    .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
            long persistedMaximum = repository.findMaxSequenceByRunIdAndTenantId(runId, actor.tenantId()).orElse(0L);
            run.ensureEventSequenceAtLeast(persistedMaximum);
            RunEventView view = RunEventView.from(repository.save(new RunEventEntity(
                    actor.tenantId(), runId, run.nextEventSequence(), type, payload, Instant.now())));
            publishAfterCommit(runId, actor.tenantId());
            return view;
            }
        } finally {
            releaseLock(runId, lock);
        }
    }

    private void publishAfterCommit(String runId, String tenantId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            broadcastCommitted(runId, tenantId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                broadcastCommitted(runId, tenantId);
            }
        });
    }

    /**
     * Each subscriber has its own delivered sequence. Querying durable events
     * from that cursor makes callback scheduling order irrelevant and keeps live
     * delivery consistent with Last-Event-ID replay.
     */
    private void broadcastCommitted(String runId, String tenantId) {
        // SseEmitter.send may block on a slow or abandoned client. Never perform that work on
        // the model/event-persistence thread: otherwise one stale browser can stop token
        // persistence, prevent terminal state updates, and leave a Run stuck in RUNNING.
        for (Subscriber subscriber : emitters.getOrDefault(runId, List.of())) {
            scheduleDrain(runId, tenantId, subscriber);
        }
    }

    private void scheduleDrain(String runId, String tenantId, Subscriber subscriber) {
        subscriber.pending.set(true);
        if (!subscriber.draining.compareAndSet(false, true)) {
            return;
        }
        Thread.startVirtualThread(() -> {
            try {
                drainSubscriber(runId, tenantId, subscriber);
            } finally {
                subscriber.draining.set(false);
                // An event can commit after the final database read and before the flag clears.
                if (subscriber.pending.get()
                        && emitters.getOrDefault(runId, List.of()).contains(subscriber)) {
                    scheduleDrain(runId, tenantId, subscriber);
                }
            }
        });
    }

    private void drainSubscriber(String runId, String tenantId, Subscriber subscriber) {
        while (emitters.getOrDefault(runId, List.of()).contains(subscriber)) {
            subscriber.pending.set(false);
            long afterSequence;
            synchronized (subscriber) {
                afterSequence = subscriber.lastSequence;
            }
            // PostgreSQL maps @Lob String to a large object.  This virtual thread is not
            // invoked through Spring's transactional proxy, so materialize the payloads
            // inside an explicit read transaction before sending them to the browser.
            List<RunEventView> pending = transactions.execute(status -> repository
                    .findByRunIdAndTenantIdAndSequenceGreaterThanOrderBySequenceAsc(
                            runId, tenantId, afterSequence)
                    .stream()
                    .map(RunEventView::from)
                    .toList());
            if (pending.isEmpty()) {
                if (!subscriber.pending.get()) {
                    return;
                }
                continue;
            }
            for (RunEventView event : pending) {
                synchronized (subscriber) {
                    if (!trySend(subscriber.emitter, event)) {
                        remove(runId, subscriber.emitter);
                        return;
                    }
                    subscriber.lastSequence = event.sequence();
                    if (isTerminal(event.type())) {
                        subscriber.emitter.complete();
                        remove(runId, subscriber.emitter);
                        return;
                    }
                }
            }
        }
    }

    /** Keeps reverse proxies from closing an otherwise healthy long-running stream. */
    @Scheduled(fixedDelayString = "${app.run.sse-heartbeat-ms:15000}")
    void sendHeartbeats() {
        for (Map.Entry<String, List<Subscriber>> entry : emitters.entrySet()) {
            for (Subscriber subscriber : entry.getValue()) {
                synchronized (subscriber) {
                    try {
                        subscriber.emitter.send(SseEmitter.event().comment("keepalive"));
                    } catch (Exception ignored) {
                        remove(entry.getKey(), subscriber.emitter);
                    }
                }
            }
        }
    }

    @Transactional(readOnly = true)
    public List<RunEventView> replay(String runId, long afterSequence, ActorContext actor) {
        return repository.findByRunIdAndTenantIdAndSequenceGreaterThanOrderBySequenceAsc(
                        runId, actor.tenantId(), afterSequence)
                .stream()
                .map(RunEventView::from)
                .toList();
    }

    public void register(String runId, SseEmitter emitter) {
        register(runId, emitter, 0L);
    }

    private void register(String runId, SseEmitter emitter, long lastSequence) {
        emitters.compute(runId, (key, existing) -> {
            List<Subscriber> list = existing == null
                    ? new java.util.concurrent.CopyOnWriteArrayList<>()
                    : existing;
            list.add(new Subscriber(emitter, lastSequence));
            return list;
        });
        emitter.onCompletion(() -> remove(runId, emitter));
        emitter.onTimeout(() -> remove(runId, emitter));
        emitter.onError(error -> remove(runId, emitter));
    }

    @Transactional
    public void replayAndRegister(String runId, long afterSequence, ActorContext actor, SseEmitter emitter) throws IOException {
        RunLock lock = acquireLock(runId);
        try {
            synchronized (lock.monitor) {
            AgentRunEntity run = runs.findWithLockByIdAndTenantId(runId, actor.tenantId())
                    .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
            long lastDelivered = afterSequence;
            for (RunEventView event : replay(runId, afterSequence, actor)) {
                send(emitter, event);
                lastDelivered = event.sequence();
                if (isTerminal(event.type())) {
                    emitter.complete();
                    return;
                }
            }
            register(runId, emitter, lastDelivered);
            }
        } finally {
            releaseLock(runId, lock);
        }
    }

    public boolean isTerminal(RunEventType type) {
        return type == RunEventType.FINAL_ANSWER
                || type == RunEventType.RUN_FAILED
                || type == RunEventType.RUN_CANCELLED;
    }

    private void remove(String runId, SseEmitter emitter) {
        List<Subscriber> subscribers = emitters.get(runId);
        if (subscribers != null) {
            subscribers.removeIf(subscriber -> subscriber.emitter == emitter);
            if (subscribers.isEmpty()) {
                emitters.remove(runId, subscribers);
            }
        }
    }

    private RunLock acquireLock(String runId) {
        return runLocks.compute(runId, (ignored, existing) -> {
            RunLock lock = existing == null ? new RunLock() : existing;
            lock.users++;
            return lock;
        });
    }

    private void releaseLock(String runId, RunLock lock) {
        runLocks.computeIfPresent(runId, (ignored, existing) -> {
            if (existing != lock || --existing.users > 0) {
                return existing;
            }
            return null;
        });
    }

    private static void send(SseEmitter emitter, RunEventView event) throws IOException {
        emitter.send(SseEmitter.event()
                .id(Long.toString(event.sequence()))
                .name(event.type().name())
                .data(event));
    }

    private static boolean trySend(SseEmitter emitter, RunEventView event) {
        try {
            send(emitter, event);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static final class Subscriber {
        private final SseEmitter emitter;
        private final AtomicBoolean draining = new AtomicBoolean();
        private final AtomicBoolean pending = new AtomicBoolean();
        private long lastSequence;

        private Subscriber(SseEmitter emitter, long lastSequence) {
            this.emitter = emitter;
            this.lastSequence = lastSequence;
        }
    }

    private static final class RunLock {
        private final Object monitor = new Object();
        private int users;
    }
}
