package io.github.yourname.agentstudio.knowledge;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocumentEntity, String> {

    List<KnowledgeDocumentEntity> findByTenantIdAndKnowledgeBaseIdOrderByCreatedAtDesc(String tenantId, String knowledgeBaseId);

    Optional<KnowledgeDocumentEntity> findByIdAndTenantIdAndKnowledgeBaseId(String id, String tenantId, String knowledgeBaseId);

    boolean existsByTenantIdAndKnowledgeBaseIdAndContentHash(String tenantId, String knowledgeBaseId, String contentHash);

    long countByTenantIdAndKnowledgeBaseId(String tenantId, String knowledgeBaseId);

    void deleteByTenantIdAndKnowledgeBaseId(String tenantId, String knowledgeBaseId);
}
