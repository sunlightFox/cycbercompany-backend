package io.github.yourname.agentstudio.node;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;
public interface NodeToolApprovalRepository extends JpaRepository<NodeToolApprovalEntity,String> {
    List<NodeToolApprovalEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);
    List<NodeToolApprovalEntity> findByTenantIdAndRunId(String tenantId, String runId);
    Optional<NodeToolApprovalEntity> findByIdAndTenantId(String id,String tenantId);

    /** Record execution fields without merging the detached pre-decision entity. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update node_tool_approval a
               set a.executedAt = :executedAt,
                   a.executionStatus = :executionStatus,
                   a.resultJson = :resultJson,
                   a.errorMessage = :errorMessage
             where a.id = :approvalId
               and a.tenantId = :tenantId
            """)
    int recordExecution(
            String approvalId,
            String tenantId,
            String executionStatus,
            String resultJson,
            String errorMessage,
            java.time.Instant executedAt);
}
