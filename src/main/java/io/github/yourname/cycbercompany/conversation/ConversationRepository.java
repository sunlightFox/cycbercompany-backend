package io.github.yourname.cycbercompany.conversation;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConversationRepository extends JpaRepository<ConversationEntity, String> {
    Optional<ConversationEntity> findByIdAndTenantId(String id, String tenantId);

    @Query("""
            select c from conversation c
            where c.tenantId = :tenantId
              and (:includeArchived = true or c.archivedAt is null)
            order by coalesce(c.lastActivityAt, c.createdAt) desc
            """)
    List<ConversationEntity> findHistory(
            @Param("tenantId") String tenantId,
            @Param("includeArchived") boolean includeArchived,
            Pageable pageable);

    @Query(value = """
            select distinct c.* from conversation c
            left join message m on m.conversation_id = c.id and m.tenant_id = c.tenant_id
            where c.tenant_id = :tenantId
              and (:includeArchived = true or c.archived_at is null)
              and (
                    lower(c.title) like lower(concat('%', :query, '%'))
                    or lower(cast(m.content as varchar)) like lower(concat('%', :query, '%'))
              )
            order by coalesce(c.last_activity_at, c.created_at) desc
            """, nativeQuery = true)
    List<ConversationEntity> searchHistory(
            @Param("tenantId") String tenantId,
            @Param("query") String query,
            @Param("includeArchived") boolean includeArchived,
            Pageable pageable);
}
