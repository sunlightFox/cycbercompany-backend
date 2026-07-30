package io.github.yourname.agentstudio.conversation;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationRepository extends JpaRepository<ConversationEntity, String> {
    Optional<ConversationEntity> findByIdAndTenantId(String id, String tenantId);
}
