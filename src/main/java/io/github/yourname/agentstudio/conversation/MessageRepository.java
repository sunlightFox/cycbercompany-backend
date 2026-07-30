package io.github.yourname.agentstudio.conversation;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageRepository extends JpaRepository<MessageEntity, Long> {
    List<MessageEntity> findByConversationIdAndTenantIdOrderByCreatedAtAsc(String conversationId, String tenantId);
}
