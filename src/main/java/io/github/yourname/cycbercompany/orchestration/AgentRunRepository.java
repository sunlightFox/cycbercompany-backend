package io.github.yourname.cycbercompany.orchestration;

import java.util.Optional;
import java.util.List;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentRunRepository extends JpaRepository<AgentRunEntity, String> {
    Optional<AgentRunEntity> findByIdAndTenantId(String id, String tenantId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AgentRunEntity> findWithLockByIdAndTenantId(String id, String tenantId);
    List<AgentRunEntity> findByConversationIdAndTenantIdOrderByCreatedAtAsc(String conversationId, String tenantId);
}
