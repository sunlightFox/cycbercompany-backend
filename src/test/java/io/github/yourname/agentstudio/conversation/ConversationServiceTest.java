package io.github.yourname.agentstudio.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
        verify(conversations).save(entity);
    }

    @Test
    void listReturnsCompactHistorySortedByRepositoryAndExcludesArchivedByDefault() {
        ConversationRepository conversations = mock(ConversationRepository.class);
        MessageRepository messages = mock(MessageRepository.class);
        Instant earlier = Instant.parse("2026-08-01T08:00:00Z");
        Instant later = Instant.parse("2026-08-01T09:00:00Z");
        ConversationEntity newest = new ConversationEntity("conversation-2", ACTOR.tenantId(), "Newest", earlier);
        newest.recordActivity(later);
        ConversationEntity oldest = new ConversationEntity("conversation-1", ACTOR.tenantId(), "Oldest", earlier);
        when(conversations.findHistory(eq(ACTOR.tenantId()), eq(false), any())).thenReturn(List.of(newest, oldest));
        when(messages.findFirstByConversationIdAndTenantIdOrderByCreatedAtDesc("conversation-2", ACTOR.tenantId()))
                .thenReturn(Optional.of(new MessageEntity(ACTOR.tenantId(), "conversation-2", MessageRole.USER,
                        "Latest message", null, later)));
        when(messages.findFirstByConversationIdAndTenantIdOrderByCreatedAtDesc("conversation-1", ACTOR.tenantId()))
                .thenReturn(Optional.empty());
        ConversationService service = new ConversationService(conversations, messages);

        var result = service.list(32, false, ACTOR);

        assertThat(result).extracting(ConversationSummaryView::id).containsExactly("conversation-2", "conversation-1");
        assertThat(result.getFirst().lastActivityAt()).isEqualTo(later);
        assertThat(result.getFirst().lastMessagePreview()).isEqualTo("Latest message");
        verify(conversations).findHistory(eq(ACTOR.tenantId()), eq(false), any());
    }

    @Test
    void searchIncludesArchivedConversationsAndCapsRequestedLimit() {
        ConversationRepository conversations = mock(ConversationRepository.class);
        MessageRepository messages = mock(MessageRepository.class);
        ConversationEntity archived = new ConversationEntity("conversation-1", ACTOR.tenantId(), "Release notes", Instant.now());
        archived.archive(Instant.now());
        when(conversations.searchHistory(eq(ACTOR.tenantId()), eq("release"), eq(true), any())).thenReturn(List.of(archived));
        when(messages.findFirstByConversationIdAndTenantIdOrderByCreatedAtDesc(archived.id(), ACTOR.tenantId()))
                .thenReturn(Optional.empty());
        ConversationService service = new ConversationService(conversations, messages);

        var result = service.search(" release ", 1_000, true, ACTOR);

        assertThat(result).singleElement().extracting(ConversationSummaryView::archived).isEqualTo(true);
        verify(conversations).searchHistory(eq(ACTOR.tenantId()), eq("release"), eq(true),
                org.mockito.ArgumentMatchers.argThat((org.springframework.data.domain.Pageable page) -> page.getPageSize() == 100));
    }
}
