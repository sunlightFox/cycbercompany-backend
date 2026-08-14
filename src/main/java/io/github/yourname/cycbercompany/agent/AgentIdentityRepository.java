package io.github.yourname.cycbercompany.agent;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentIdentityRepository extends JpaRepository<AgentIdentityEntity, String> {
    Optional<AgentIdentityEntity> findByIdAndTenantId(String id, String tenantId);
    List<AgentIdentityEntity> findAllByTenantIdAndStatusOrderByUpdatedAtDesc(String tenantId, String status);
    List<AgentIdentityEntity> findAllByTenantIdOrderByUpdatedAtDesc(String tenantId);
}
