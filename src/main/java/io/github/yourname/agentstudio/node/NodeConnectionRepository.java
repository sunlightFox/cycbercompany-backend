package io.github.yourname.agentstudio.node;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NodeConnectionRepository extends JpaRepository<NodeConnectionEntity, String> {

    List<NodeConnectionEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    Optional<NodeConnectionEntity> findByTenantIdAndKind(String tenantId, NodeKind kind);

    Optional<NodeConnectionEntity> findByIdAndTenantId(String id, String tenantId);
}
