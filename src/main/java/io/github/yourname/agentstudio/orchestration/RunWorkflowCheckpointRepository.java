package io.github.yourname.agentstudio.orchestration;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RunWorkflowCheckpointRepository extends JpaRepository<RunWorkflowCheckpointEntity, String> {
    Optional<RunWorkflowCheckpointEntity> findByRunIdAndTenantId(String runId, String tenantId);
}
