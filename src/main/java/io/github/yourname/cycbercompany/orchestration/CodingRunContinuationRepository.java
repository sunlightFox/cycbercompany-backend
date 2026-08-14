package io.github.yourname.cycbercompany.orchestration;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CodingRunContinuationRepository extends JpaRepository<CodingRunContinuationEntity, String> {

    Optional<CodingRunContinuationEntity> findByRunIdAndTenantId(String runId, String tenantId);
}
