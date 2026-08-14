package io.github.yourname.cycbercompany.orchestration;

import io.github.yourname.cycbercompany.conversation.ConversationRepository;
import io.github.yourname.cycbercompany.security.ActorContext;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RunQueryService {

    private final AgentRunRepository runs;
    private final ConversationRunQueue queue;
    private final ConversationRepository conversations;

    public RunQueryService(AgentRunRepository runs, ConversationRunQueue queue, ConversationRepository conversations) {
        this.runs = runs;
        this.queue = queue;
        this.conversations = conversations;
    }

    @Transactional(readOnly = true)
    public RunView get(String runId, ActorContext actor) {
        // 运行 ID 不是访问凭证，查询时仍必须按当前租户过滤。
        return runs.findByIdAndTenantId(runId, actor.tenantId())
                .filter(run -> run.userId() == null || actor.userId().equals(run.userId()))
                .map(run -> RunView.from(
                        run,
                        queue.position(new ConversationRunQueue.QueueKey(actor.tenantId(), run.conversationId()), run.id())))
                .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
    }

    @Transactional(readOnly = true)
    public List<RunView> listConversationRuns(String conversationId, ActorContext actor) {
        conversations.findByIdAndTenantId(conversationId, actor.tenantId())
                .filter(value -> value.userId() == null || actor.userId().equals(value.userId()))
                .orElseThrow(() -> new IllegalArgumentException("Conversation not found: " + conversationId));
        return runs.findByConversationIdAndTenantIdOrderByCreatedAtAsc(conversationId, actor.tenantId()).stream()
                .filter(run -> run.userId() == null || actor.userId().equals(run.userId()))
                .map(run -> RunView.from(
                        run,
                        queue.position(new ConversationRunQueue.QueueKey(actor.tenantId(), run.conversationId()), run.id())))
                .toList();
    }
}
