package io.github.yourname.cycbercompany.orchestration;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;

/** 负责写入、领取和确认 Run 调度 outbox 消息。 */
@Service
public class RunExecutionOutboxService {

    private static final Duration DELIVERY_LEASE = Duration.ofMinutes(1);
    private final RunExecutionOutboxRepository messages;

    public RunExecutionOutboxService(RunExecutionOutboxRepository messages) {
        this.messages = messages;
    }

    /** 在创建 Run 的同一事务内调用，不能异步写入。 */
    @Transactional
    public void enqueue(AgentRunEntity run) {
        messages.save(new RunExecutionOutboxEntity(
                "runoutbox_" + UUID.randomUUID(), run.tenantId(), run.id(), Instant.now()));
    }

    /**
     * 领取少量可用消息后立即提交事务，实际调度在事务外执行，避免把数据库锁带进模型调用。
     */
    @Transactional
    public List<ClaimedMessage> claimPending(int limit) {
        Instant now = Instant.now();
        return messages.findDispatchableIds(now, PageRequest.of(0, Math.max(1, limit))).stream()
                .map(id -> claim(id, now))
                .flatMap(Optional::stream)
                .toList();
    }

    private Optional<ClaimedMessage> claim(String id, Instant now) {
        return messages.findByIdForUpdate(id)
                .filter(message -> {
                    String leaseId = "outboxlease_" + UUID.randomUUID();
                    if (!message.claim(leaseId, now, DELIVERY_LEASE)) {
                        return false;
                    }
                    messages.save(message);
                    return true;
                })
                .map(message -> new ClaimedMessage(message.id(), message.runId(), message.leaseId()));
    }

    @Transactional
    public void markProcessed(String messageId, String leaseId) {
        messages.findByIdForUpdate(messageId).ifPresent(message -> {
            message.processed(leaseId, Instant.now());
            messages.save(message);
        });
    }

    @Transactional
    public void retry(String messageId, String leaseId, Exception failure) {
        messages.findByIdForUpdate(messageId).ifPresent(message -> {
            int seconds = Math.min(60, 1 << Math.min(6, Math.max(0, message.deliveryAttempt() - 1)));
            Instant now = Instant.now();
            message.retry(leaseId, safeMessage(failure), now.plusSeconds(seconds), now);
            messages.save(message);
        });
    }

    private static String safeMessage(Exception failure) {
        if (failure == null || failure.getMessage() == null || failure.getMessage().isBlank()) {
            return "Unable to schedule persisted run.";
        }
        return failure.getMessage().substring(0, Math.min(1_000, failure.getMessage().length()));
    }

    public record ClaimedMessage(String id, String runId, String leaseId) {
    }
}
