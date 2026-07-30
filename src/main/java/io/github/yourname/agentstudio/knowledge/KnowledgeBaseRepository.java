package io.github.yourname.agentstudio.knowledge;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBaseEntity, String> {
    List<KnowledgeBaseEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);
    Optional<KnowledgeBaseEntity> findByIdAndTenantId(String id, String tenantId);
}
