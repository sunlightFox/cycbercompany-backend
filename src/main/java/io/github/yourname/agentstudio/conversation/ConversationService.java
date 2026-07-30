package io.github.yourname.agentstudio.conversation;

import io.github.yourname.agentstudio.security.ActorContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Conversation command/query facade.
 *
 * <p>Only this module writes messages. Orchestration calls it after model runs
 * complete, which keeps chat history persistence separate from runtime event
 * persistence and makes SSE recovery easier to reason about.
 */
@Service
public class ConversationService {

    private final ConversationRepository conversations;
    private final MessageRepository messages;

    public ConversationService(ConversationRepository conversations, MessageRepository messages) {
        this.conversations = conversations;
        this.messages = messages;
    }

    @Transactional
    public ConversationView create(CreateConversationCommand command, ActorContext actor) {
        String title = command.title() == null || command.title().isBlank() ? "New conversation" : command.title().trim();
        var entity = conversations.save(new ConversationEntity(UUID.randomUUID().toString(), actor.tenantId(), title, Instant.now()));
        return new ConversationView(entity.id(), entity.title(), entity.createdAt(), List.of());
    }

    @Transactional(readOnly = true)
    public ConversationView get(String id, ActorContext actor) {
        var conversation = conversations.findByIdAndTenantId(id, actor.tenantId())
                .orElseThrow(() -> new IllegalArgumentException("Conversation not found: " + id));
        var messageViews = messages.findByConversationIdAndTenantIdOrderByCreatedAtAsc(id, actor.tenantId())
                .stream().map(MessageView::from).toList();
        return new ConversationView(conversation.id(), conversation.title(), conversation.createdAt(), messageViews);
    }

    @Transactional
    public void append(String conversationId, MessageRole role, String content, String runId, ActorContext actor) {
        conversations.findByIdAndTenantId(conversationId, actor.tenantId())
                .orElseThrow(() -> new IllegalArgumentException("Conversation not found: " + conversationId));
        messages.save(new MessageEntity(actor.tenantId(), conversationId, role, content, runId, Instant.now()));
    }

    @Transactional(readOnly = true)
    public List<MessageView> history(String conversationId, ActorContext actor) {
        return messages.findByConversationIdAndTenantIdOrderByCreatedAtAsc(conversationId, actor.tenantId())
                .stream().map(MessageView::from).toList();
    }
}
