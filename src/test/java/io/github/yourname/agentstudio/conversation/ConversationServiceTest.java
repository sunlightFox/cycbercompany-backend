package io.github.yourname.agentstudio.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.yourname.agentstudio.security.ActorContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ConversationServiceTest {

    private static final ActorContext ACTOR =
            new ActorContext("tenant-a", "alice", java.util.Set.of(), java.util.Set.of());

    @Test
    void archiveMarksConversationAsReadOnly() {
        ConversationRepository conversations = mock(ConversationRepository.class);
        MessageRepository messages = mock(MessageRepository.class);
        ConversationEntity entity = new ConversationEntity("conversation-1", ACTOR.tenantId(), "Test", Instant.now());
        when(conversations.findByIdAndTenantId(entity.id(), ACTOR.tenantId())).thenReturn(Optional.of(entity));
        when(messages.findByConversationIdAndTenantIdOrderByCreatedAtAsc(entity.id(), ACTOR.tenantId())).thenReturn(List.of());
        when(conversations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ConversationService service = new ConversationService(conversations, messages);

        ConversationView archived = service.archive(entity.id(), ACTOR);

        assertThat(archived.archived()).isTrue();
        assertThat(archived.archivedAt()).isNotNull();
        verify(conversations).save(entity);
    }

    @Test
    void appendRejectsNewUserMessagesAfterArchive() {
        ConversationRepository conversations = mock(ConversationRepository.class);
        MessageRepository messages = mock(MessageRepository.class);
        ConversationEntity entity = new ConversationEntity("conversation-1", ACTOR.tenantId(), "Test", Instant.now());
        entity.archive(Instant.now());
        when(conversations.findByIdAndTenantId(entity.id(), ACTOR.tenantId())).thenReturn(Optional.of(entity));
        ConversationService service = new ConversationService(conversations, messages);

        assertThatThrownBy(() -> service.append(entity.id(), MessageRole.USER, "hello", "run-1", ACTOR))
                .isInstanceOf(ConversationArchivedException.class)
                .hasMessage("Conversation is archived: conversation-1");
    }

    @Test
    void appendStillAllowsAssistantMessagesForAnArchivedConversation() {
        ConversationRepository conversations = mock(ConversationRepository.class);
        MessageRepository messages = mock(MessageRepository.class);
        ConversationEntity entity = new ConversationEntity("conversation-1", ACTOR.tenantId(), "Test", Instant.now());
        entity.archive(Instant.now());
        when(conversations.findByIdAndTenantId(entity.id(), ACTOR.tenantId())).thenReturn(Optional.of(entity));
        when(messages.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ConversationService service = new ConversationService(conversations, messages);

        service.append(entity.id(), MessageRole.ASSISTANT, "done", "run-1", ACTOR);

        verify(messages).save(any());
    }
}
