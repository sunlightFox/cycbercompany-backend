package io.github.yourname.agentstudio.orchestration;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RunEventRepository extends JpaRepository<RunEventEntity, Long> {
    List<RunEventEntity> findByRunIdAndTenantIdAndSequenceGreaterThanOrderBySequenceAsc(String runId, String tenantId, long sequence);
    long countByRunIdAndTenantId(String runId, String tenantId);
}
