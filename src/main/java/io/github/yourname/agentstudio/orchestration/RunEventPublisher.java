package io.github.yourname.agentstudio.orchestration;

import io.github.yourname.agentstudio.security.ActorContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Persists events before pushing them to live SSE subscribers.
 *
 * <p>This is the central reliability rule for the chat protocol: a browser
 * connection can disappear without losing the run, because replay reads from
 * the database using the event sequence.
 */
@Component
public class RunEventPublisher {

    private final RunEventRepository repository;
    private final Map<String, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public RunEventPublisher(RunEventRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public RunEventView publish(String runId, RunEventType type, String payload, ActorContext actor) {
        long nextSequence = repository.countByRunIdAndTenantId(runId, actor.tenantId()) + 1;
        var saved = repository.save(new RunEventEntity(actor.tenantId(), runId, nextSequence, type, payload, Instant.now()));
        var view = RunEventView.from(saved);
        broadcast(runId, view);
        return view;
    }

    @Transactional(readOnly = true)
    public List<RunEventView> replay(String runId, long afterSequence, ActorContext actor) {
        return repository.findByRunIdAndTenantIdAndSequenceGreaterThanOrderBySequenceAsc(runId, actor.tenantId(), afterSequence)
                .stream().map(RunEventView::from).toList();
    }

    public void register(String runId, SseEmitter emitter) {
        emitters.compute(runId, (key, existing) -> {
            var list = existing == null ? new java.util.concurrent.CopyOnWriteArrayList<SseEmitter>() : existing;
            list.add(emitter);
            return list;
        });
        emitter.onCompletion(() -> remove(runId, emitter));
        emitter.onTimeout(() -> remove(runId, emitter));
        emitter.onError(error -> remove(runId, emitter));
    }

    private void broadcast(String runId, RunEventView event) {
        var current = emitters.getOrDefault(runId, List.of());
        for (SseEmitter emitter : current) {
            try {
                emitter.send(SseEmitter.event()
                        .id(Long.toString(event.sequence()))
                        .name(event.type().name())
                        .data(event));
            } catch (Exception ignored) {
                remove(runId, emitter);
            }
        }
    }

    private void remove(String runId, SseEmitter emitter) {
        var list = emitters.get(runId);
        if (list != null) {
            list.remove(emitter);
        }
    }
}
