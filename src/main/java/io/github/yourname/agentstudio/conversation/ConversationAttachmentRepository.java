package io.github.yourname.agentstudio.conversation;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationAttachmentRepository extends JpaRepository<ConversationAttachmentEntity, String> {
    Optional<ConversationAttachmentEntity> findByIdAndTenantId(String id, String tenantId);
}
