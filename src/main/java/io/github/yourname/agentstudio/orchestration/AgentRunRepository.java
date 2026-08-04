package io.github.yourname.agentstudio.orchestration;

import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentRunRepository extends JpaRepository<AgentRunEntity, String> {
    Optional<AgentRunEntity> findByIdAndTenantId(String id, String tenantId);
    List<AgentRunEntity> findByConversationIdAndTenantIdOrderByCreatedAtAsc(String conversationId, String tenantId);
}
