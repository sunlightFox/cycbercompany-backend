package io.github.yourname.agentstudio.agent;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentEvaluationRepository extends JpaRepository<AgentEvaluationEntity, String> {
    Optional<AgentEvaluationEntity> findTopByTenantIdAndVersionIdAndManifestDigestAndSuiteIdOrderByCreatedAtDesc(
            String tenantId, String versionId, String manifestDigest, String suiteId);
}
