package io.github.yourname.cycbercompany.orchestration;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RunEventRepository extends JpaRepository<RunEventEntity, Long> {
    List<RunEventEntity> findByRunIdAndTenantIdAndSequenceGreaterThanOrderBySequenceAsc(String runId, String tenantId, long sequence);
    long countByRunIdAndTenantId(String runId, String tenantId);
    @Query("select max(event.sequence) from run_event event where event.runId = :runId and event.tenantId = :tenantId")
    Optional<Long> findMaxSequenceByRunIdAndTenantId(String runId, String tenantId);
}
