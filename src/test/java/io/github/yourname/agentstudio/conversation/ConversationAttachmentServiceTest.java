package io.github.yourname.agentstudio.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.yourname.agentstudio.config.AppProperties;
import io.github.yourname.agentstudio.security.ActorContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

class ConversationAttachmentServiceTest {

    private static final ActorContext ACTOR =
            new ActorContext("tenant-a", "alice", java.util.Set.of(), java.util.Set.of());

    @TempDir
    Path tempDir;

    @Test
    void storesTextAttachmentsAndAddsABoundedExcerptToTheModelContext() throws Exception {
        ConversationRepository conversations = mock(ConversationRepository.class);
        ConversationAttachmentRepository attachments = mock(ConversationAttachmentRepository.class);
        when(conversations.findByIdAndTenantId("conversation-1", ACTOR.tenantId())).thenReturn(Optional.of(
                new ConversationEntity("conversation-1", ACTOR.tenantId(), "Test", Instant.now())));
        when(attachments.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ConversationAttachmentService service = new ConversationAttachmentService(
                new AppProperties(tempDir, null, null, null, null, null, null), conversations, attachments);
        var upload = new MockMultipartFile(
                "files", "notes.md", "text/markdown", "# Notes\nUse the supplied facts.".getBytes(StandardCharsets.UTF_8));

        var stored = service.upload("conversation-1", List.of(upload), ACTOR);
        ConversationAttachmentEntity entity = new ConversationAttachmentEntity(
                stored.getFirst().id(),
                ACTOR.tenantId(),
                "conversation-1",
                "notes.md",
                "text/markdown",
                upload.getSize(),
                stored.getFirst().id() + ".bin",
                Instant.now());
        when(attachments.findByIdAndTenantId(entity.id(), ACTOR.tenantId())).thenReturn(Optional.of(entity));

        String context = service.modelContext("conversation-1", List.of(entity.id()), ACTOR);

        assertThat(stored).singleElement().satisfies(view -> {
            assertThat(view.fileName()).isEqualTo("notes.md");
            assertThat(view.contentType()).isEqualTo("text/markdown");
        });
        assertThat(Files.readString(tempDir.resolve("attachments").resolve(entity.storageKey())))
                .contains("Use the supplied facts.");
        assertThat(context).contains("notes.md", "<attachment-content>", "Use the supplied facts.");
    }

    @Test
    void rejectsAttachmentFromAnotherConversation() {
        ConversationAttachmentRepository attachments = mock(ConversationAttachmentRepository.class);
        ConversationAttachmentEntity entity = new ConversationAttachmentEntity(
                "attachment-1", ACTOR.tenantId(), "other-conversation", "secret.txt", "text/plain", 1, "attachment-1.bin", Instant.now());
        when(attachments.findByIdAndTenantId(entity.id(), ACTOR.tenantId())).thenReturn(Optional.of(entity));
        ConversationAttachmentService service = new ConversationAttachmentService(
                new AppProperties(tempDir, null, null, null, null, null, null), mock(ConversationRepository.class), attachments);

        assertThatThrownBy(() -> service.modelContext("conversation-1", List.of(entity.id()), ACTOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Attachment does not belong to this conversation.");
    }
}
