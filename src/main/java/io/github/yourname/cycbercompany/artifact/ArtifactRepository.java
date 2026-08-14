package io.github.yourname.cycbercompany.artifact;

import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface ArtifactRepository extends JpaRepository<ArtifactEntity, String> {
    Optional<ArtifactEntity> findByIdAndTenantId(String id, String tenantId);
    List<ArtifactEntity> findByRunIdAndTenantIdOrderByCreatedAtAsc(String runId, String tenantId);
}
