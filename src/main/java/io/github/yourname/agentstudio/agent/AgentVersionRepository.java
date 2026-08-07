package io.github.yourname.agentstudio.agent;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentVersionRepository extends JpaRepository<AgentVersionEntity, String> {
    Optional<AgentVersionEntity> findByIdAndAgentIdAndTenantId(String id, String agentId, String tenantId);
    List<AgentVersionEntity> findAllByAgentIdAndTenantIdOrderByVersionNumberDesc(String agentId, String tenantId);
    Optional<AgentVersionEntity> findTopByAgentIdAndTenantIdOrderByVersionNumberDesc(String agentId, String tenantId);
    Optional<AgentVersionEntity> findTopByAgentIdAndTenantIdAndStateOrderByVersionNumberDesc(
            String agentId, String tenantId, String state);
}
