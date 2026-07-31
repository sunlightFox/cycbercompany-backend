package io.github.yourname.agentstudio.node;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
public interface NodeToolApprovalRepository extends JpaRepository<NodeToolApprovalEntity,String> {
    List<NodeToolApprovalEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);
    Optional<NodeToolApprovalEntity> findByIdAndTenantId(String id,String tenantId);
}
