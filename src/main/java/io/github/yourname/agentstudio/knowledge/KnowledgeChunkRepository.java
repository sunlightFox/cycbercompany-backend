package io.github.yourname.agentstudio.knowledge;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunkEntity, Long> {

    boolean existsByTenantIdAndKnowledgeBaseIdAndContentHash(String tenantId, String knowledgeBaseId, String contentHash);

    @Query("""
            select chunk from knowledge_chunk chunk
            where chunk.tenantId = :tenantId
              and chunk.knowledgeBaseId in :knowledgeBaseIds
              and lower(chunk.content) like lower(concat('%', :term, '%'))
            order by chunk.id desc
            """)
    List<KnowledgeChunkEntity> search(
            @Param("tenantId") String tenantId,
            @Param("knowledgeBaseIds") Collection<String> knowledgeBaseIds,
            @Param("term") String term);
}
