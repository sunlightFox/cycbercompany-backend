package io.github.yourname.agentstudio.orchestration;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentRunRepository extends JpaRepository<AgentRunEntity, String> {
    Optional<AgentRunEntity> findByIdAndTenantId(String id, String tenantId);
}
