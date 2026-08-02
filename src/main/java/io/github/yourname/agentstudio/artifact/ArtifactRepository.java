package io.github.yourname.agentstudio.artifact;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface ArtifactRepository extends JpaRepository<ArtifactEntity, String> {
    Optional<ArtifactEntity> findByIdAndTenantId(String id, String tenantId);
}
