package io.github.yourname.cycbercompany.conversation;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationAttachmentRepository extends JpaRepository<ConversationAttachmentEntity, String> {
    Optional<ConversationAttachmentEntity> findByIdAndTenantId(String id, String tenantId);

    List<ConversationAttachmentEntity> findAllByConversationIdAndTenantIdOrderByCreatedAtAsc(
            String conversationId, String tenantId);
}
