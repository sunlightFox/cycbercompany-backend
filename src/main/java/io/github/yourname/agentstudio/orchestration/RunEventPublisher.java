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
 * 负责运行事件的“先持久化、后推送”。
 *
 * <p>这是聊天 SSE 协议最重要的可靠性规则：浏览器断开时，运行本身仍继续，客户端可带着
 * {@code Last-Event-ID} 按事件序号从数据库补读。内存中的 {@link SseEmitter} 只是实时通知渠道，
 * 不能当作事实来源。
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
        // 序号是单个 run 内的游标，前端用它判断从哪一条事件继续重放。
        long nextSequence = repository.countByRunIdAndTenantId(runId, actor.tenantId()) + 1;
        var saved = repository.save(new RunEventEntity(actor.tenantId(), runId, nextSequence, type, payload, Instant.now()));
        var view = RunEventView.from(saved);
        broadcast(runId, view);
        return view;
    }

    @Transactional(readOnly = true)
    public List<RunEventView> replay(String runId, long afterSequence, ActorContext actor) {
        // 使用“大于”而非“大于等于”，避免重连时把客户端已经处理的最后一条再发送一次。
        return repository.findByRunIdAndTenantIdAndSequenceGreaterThanOrderBySequenceAsc(runId, actor.tenantId(), afterSequence)
                .stream().map(RunEventView::from).toList();
    }

    public void register(String runId, SseEmitter emitter) {
        // CopyOnWriteArrayList 适合“订阅者少、广播多”的 SSE 场景，遍历时不会并发修改异常。
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
                if (isTerminal(event.type())) {
                    // 最终事件已送达后主动关闭连接，释放 servlet 异步请求占用的资源。
                    emitter.complete();
                    remove(runId, emitter);
                }
            } catch (Exception ignored) {
                // 单个慢客户端或已断开的连接不能阻塞其他订阅者的事件投递。
                remove(runId, emitter);
            }
        }
    }

    public boolean isTerminal(RunEventType type) {
        return type == RunEventType.FINAL_ANSWER
                || type == RunEventType.RUN_FAILED
                || type == RunEventType.RUN_CANCELLED;
    }

    private void remove(String runId, SseEmitter emitter) {
        var list = emitters.get(runId);
        if (list != null) {
            list.remove(emitter);
        }
    }
}
