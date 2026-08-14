package io.github.yourname.cycbercompany.tool;

import java.util.List;
import java.util.Optional;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;

interface ToolApprovalRepository extends JpaRepository<ToolApprovalEntity, String> {
    Optional<ToolApprovalEntity> findByTenantIdAndRunIdAndToolCallId(
            String tenantId, String runId, String toolCallId);
    Optional<ToolApprovalEntity> findByIdAndTenantId(String id, String tenantId);
    List<ToolApprovalEntity> findByTenantIdOrderByRequestedAtDesc(String tenantId);
    List<ToolApprovalEntity> findByStatusAndExpiresAtLessThanEqual(ToolApprovalStatus status, Instant now);
}
