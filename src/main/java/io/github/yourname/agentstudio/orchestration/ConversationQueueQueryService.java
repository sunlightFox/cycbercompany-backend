package io.github.yourname.agentstudio.orchestration;

import io.github.yourname.agentstudio.conversation.ConversationRepository;
import io.github.yourname.agentstudio.security.ActorContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConversationQueueQueryService {

    private final ConversationRepository conversations;
    private final ConversationRunQueue queue;

    public ConversationQueueQueryService(ConversationRepository conversations, ConversationRunQueue queue) {
        this.conversations = conversations;
        this.queue = queue;
    }

    @Transactional(readOnly = true)
    public ConversationQueueView get(String conversationId, ActorContext actor) {
        conversations.findByIdAndTenantId(conversationId, actor.tenantId())
                .filter(value -> value.userId() == null || actor.userId().equals(value.userId()))
                .orElseThrow(() -> new IllegalArgumentException("Conversation not found: " + conversationId));
        return ConversationQueueView.from(
                conversationId,
                queue.snapshot(new ConversationRunQueue.QueueKey(actor.tenantId(), conversationId)));
    }
}
