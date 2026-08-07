package io.github.yourname.agentstudio.conversation;

import io.github.yourname.agentstudio.security.ActorContext;
import io.github.yourname.agentstudio.persona.UserPersonaContext;
import io.github.yourname.agentstudio.persona.UserPersonaEntity;
import io.github.yourname.agentstudio.persona.UserPersonaRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * 会话应用服务。
 *
 * <p>它的职责很克制：只负责会话和消息本身的生命周期。Run 创建、Prompt 拼装、
 * 模型调用、工具调用都在 {@code orchestration} 中完成。这样新手排查聊天问题时，
 * 可以先判断问题属于“数据是否保存”还是“Run 是否执行”。
 */
@Service
public class ConversationService {

    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private UserPersonaRepository personas;

    public ConversationService(ConversationRepository conversations, MessageRepository messages) {
        this.conversations = conversations;
        this.messages = messages;
    }

    @Autowired
    void configureUserPersonas(UserPersonaRepository personas) {
        this.personas = personas;
    }

    @Transactional
    public ConversationView create(CreateConversationCommand command, ActorContext actor) {
        // 标题允许省略，前端可以先创建空会话，再随着第一条消息更新显示名称。
        String title = command.title() == null || command.title().isBlank() ? "New conversation" : command.title().trim();
        var entity = new ConversationEntity(UUID.randomUUID().toString(), actor.tenantId(), title, Instant.now());
        UserPersonaEntity persona = resolveInitialPersona(command.personaId(), actor);
        entity.selectPersona(persona == null ? null : persona.id());
        entity = conversations.save(entity);
        return ConversationView.from(entity, List.of());
    }

    @Transactional(readOnly = true)
    public ConversationView get(String id, ActorContext actor) {
        var conversation = requireConversation(id, actor);
        // 历史消息总是按创建时间升序返回，编排层拼接模型上下文时依赖这个稳定顺序。
        var messageViews = messages.findByConversationIdAndTenantIdOrderByCreatedAtAsc(id, actor.tenantId())
                .stream().map(MessageView::from).toList();
        return ConversationView.from(conversation, messageViews);
    }

    @Transactional
    public ConversationView archive(String id, ActorContext actor) {
        var conversation = requireConversation(id, actor);
        conversation.archive(Instant.now());
        conversations.save(conversation);
        var messageViews = messages.findByConversationIdAndTenantIdOrderByCreatedAtAsc(id, actor.tenantId())
                .stream().map(MessageView::from).toList();
        return ConversationView.from(conversation, messageViews);
    }

    @Transactional
    public ConversationSummaryView rename(String id, RenameConversationCommand command, ActorContext actor) {
        var conversation = requireConversation(id, actor);
        conversation.rename(command.title().trim());
        conversations.save(conversation);
        return summary(conversation, actor);
    }

    @Transactional
    public ConversationView selectPersona(
            String id,
            SelectConversationPersonaCommand command,
            ActorContext actor) {
        var conversation = requireConversation(id, actor);
        if (conversation.archived()) {
            throw new ConversationArchivedException(id);
        }
        String personaId = command.personaId() == null || command.personaId().isBlank()
                ? null
                : requirePersona(command.personaId(), actor).id();
        conversation.selectPersona(personaId);
        conversations.save(conversation);
        var messageViews = messages.findByConversationIdAndTenantIdOrderByCreatedAtAsc(id, actor.tenantId())
                .stream().map(MessageView::from).toList();
        return ConversationView.from(conversation, messageViews);
    }

    @Transactional(readOnly = true)
    public UserPersonaContext personaContext(String conversationId, ActorContext actor) {
        var conversation = requireConversation(conversationId, actor);
        if (conversation.userPersonaId() == null || personas == null) {
            return null;
        }
        UserPersonaEntity persona = personas.findByIdAndTenantIdAndUserId(
                        conversation.userPersonaId(), actor.tenantId(), actor.userId())
                .orElse(null);
        if (persona == null) {
            return null;
        }
        return new UserPersonaContext(
                persona.id(), persona.name(), persona.description(), persona.attributesJson());
    }

    @Transactional(readOnly = true)
    public List<ConversationSummaryView> list(int limit, boolean includeArchived, ActorContext actor) {
        return conversations.findHistory(actor.tenantId(), includeArchived, PageRequest.of(0, boundedLimit(limit)))
                .stream().map(conversation -> summary(conversation, actor)).toList();
    }

    @Transactional(readOnly = true)
    public List<ConversationSummaryView> search(String query, int limit, boolean includeArchived, ActorContext actor) {
        String normalized = query == null ? "" : query.trim();
        if (normalized.isEmpty()) return List.of();
        return conversations.searchHistory(actor.tenantId(), normalized, includeArchived, PageRequest.of(0, boundedLimit(limit)))
                .stream().map(conversation -> summary(conversation, actor)).toList();
    }

    @Transactional(readOnly = true)
    public void ensureWritable(String conversationId, ActorContext actor) {
        // 归档会话仍可读取历史，但不能再追加用户消息或新 Run。
        if (requireConversation(conversationId, actor).archived()) {
            throw new ConversationArchivedException(conversationId);
        }
    }

    @Transactional
    public void append(String conversationId, MessageRole role, String content, String runId, ActorContext actor) {
        var conversation = requireConversation(conversationId, actor);
        if (role == MessageRole.USER && conversation.archived()) {
            throw new ConversationArchivedException(conversationId);
        }
        Instant now = Instant.now();
        messages.save(new MessageEntity(actor.tenantId(), conversationId, role, content, runId, now));
        conversation.recordActivity(now);
        conversations.save(conversation);
    }

    @Transactional(readOnly = true)
    public List<MessageView> history(String conversationId, ActorContext actor) {
        requireConversation(conversationId, actor);
        return messages.findByConversationIdAndTenantIdOrderByCreatedAtAsc(conversationId, actor.tenantId())
                .stream().map(MessageView::from).toList();
    }

    private ConversationEntity requireConversation(String id, ActorContext actor) {
        // 所有读取都带 tenantId，避免仅凭猜到的 conversationId 访问其他租户数据。
        return conversations.findByIdAndTenantId(id, actor.tenantId())
                .orElseThrow(() -> new IllegalArgumentException("Conversation not found: " + id));
    }

    private UserPersonaEntity resolveInitialPersona(String requestedPersonaId, ActorContext actor) {
        if (personas == null) {
            return null;
        }
        if (requestedPersonaId != null && !requestedPersonaId.isBlank()) {
            return requirePersona(requestedPersonaId, actor);
        }
        return personas.findFirstByTenantIdAndUserIdAndDefaultPersonaTrue(actor.tenantId(), actor.userId())
                .orElse(null);
    }

    private UserPersonaEntity requirePersona(String id, ActorContext actor) {
        if (personas == null) {
            throw new IllegalArgumentException("User personas are not available.");
        }
        return personas.findByIdAndTenantIdAndUserId(id, actor.tenantId(), actor.userId())
                .orElseThrow(() -> new IllegalArgumentException("User persona not found: " + id));
    }

    private ConversationSummaryView summary(ConversationEntity conversation, ActorContext actor) {
        String preview = messages.findFirstByConversationIdAndTenantIdOrderByCreatedAtDesc(
                        conversation.id(), actor.tenantId())
                .map(MessageEntity::content)
                .map(ConversationService::preview)
                .orElse(null);
        return ConversationSummaryView.from(conversation, preview);
    }

    private static int boundedLimit(int requestedLimit) {
        return Math.clamp(requestedLimit, 1, 100);
    }

    private static String preview(String content) {
        String normalized = content == null ? "" : content.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 160 ? normalized : normalized.substring(0, 157) + "...";
    }
}
