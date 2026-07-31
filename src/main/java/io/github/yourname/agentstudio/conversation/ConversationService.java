package io.github.yourname.agentstudio.conversation;

import io.github.yourname.agentstudio.security.ActorContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 会话模块的读写门面。
 *
 * <p>控制器和编排模块都不直接访问 Repository，而是经由此类处理会话与消息。
 * 这样聊天记录（长期业务数据）和运行事件（用于 SSE 重放的过程数据）始终分开保存，
 * 既便于理解职责，也避免一次模型运行失败影响已经写入的用户消息。
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
        // 标题是可选输入；在服务层兜底可使 HTTP、测试和未来的其他调用方行为一致。
        String title = command.title() == null || command.title().isBlank() ? "New conversation" : command.title().trim();
        var entity = conversations.save(new ConversationEntity(UUID.randomUUID().toString(), actor.tenantId(), title, Instant.now()));
        return new ConversationView(entity.id(), entity.title(), entity.createdAt(), List.of());
    }

    @Transactional(readOnly = true)
    public ConversationView get(String id, ActorContext actor) {
        // 每次查询都带 tenantId，防止仅凭猜到的 UUID 跨租户读取数据。
        var conversation = conversations.findByIdAndTenantId(id, actor.tenantId())
                .orElseThrow(() -> new IllegalArgumentException("Conversation not found: " + id));
        var messageViews = messages.findByConversationIdAndTenantIdOrderByCreatedAtAsc(id, actor.tenantId())
                .stream().map(MessageView::from).toList();
        return new ConversationView(conversation.id(), conversation.title(), conversation.createdAt(), messageViews);
    }

    @Transactional
    public void append(String conversationId, MessageRole role, String content, String runId, ActorContext actor) {
        // 先确认会话归属，再写消息；不能只依赖前端传来的 conversationId。
        conversations.findByIdAndTenantId(conversationId, actor.tenantId())
                .orElseThrow(() -> new IllegalArgumentException("Conversation not found: " + conversationId));
        messages.save(new MessageEntity(actor.tenantId(), conversationId, role, content, runId, Instant.now()));
    }

    @Transactional(readOnly = true)
    public List<MessageView> history(String conversationId, ActorContext actor) {
        // 按时间正序返回，结果可直接作为模型 messages 数组的历史上下文。
        return messages.findByConversationIdAndTenantIdOrderByCreatedAtAsc(conversationId, actor.tenantId())
                .stream().map(MessageView::from).toList();
    }
}
