package io.github.yourname.agentstudio.node;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NodeToolRepository extends JpaRepository<NodeToolEntity, Long> {

    List<NodeToolEntity> findByTenantIdAndNodeIdOrderByNameAsc(String tenantId, String nodeId);

    List<NodeToolEntity> findByTenantIdOrderByNodeIdAscNameAsc(String tenantId);

    Optional<NodeToolEntity> findByTenantIdAndNodeIdAndName(String tenantId, String nodeId, String name);

    void deleteByTenantIdAndNodeId(String tenantId, String nodeId);
}
