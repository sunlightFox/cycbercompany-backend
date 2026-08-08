package io.github.yourname.agentstudio.memory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemoryItemRepository extends JpaRepository<MemoryItemEntity, String> {

    Optional<MemoryItemEntity> findByIdAndTenantIdAndUserId(String id, String tenantId, String userId);

    boolean existsByTenantIdAndUserIdAndAgentIdAndPersonaIdAndContentIgnoreCaseAndStatusNot(
            String tenantId,
            String userId,
            String agentId,
            String personaId,
            String content,
            String status);

    @Query("""
            select m from memory_item m
            where m.tenantId = :tenantId and m.userId = :userId
              and (:agentId is null or m.agentId = :agentId)
              and (:personaId is null or m.personaId = :personaId)
              and (:sharedOnly = false or m.personaId is null)
              and (:type is null or m.type = :type)
              and (:status is null or m.status = :status)
              and (m.expiresAt is null or m.expiresAt > :now)
              and (:query is null or :query = '' or lower(m.content) like lower(concat('%', :query, '%')))
            order by m.updatedAt desc
            """)
    List<MemoryItemEntity> search(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("agentId") String agentId,
            @Param("personaId") String personaId,
            @Param("sharedOnly") boolean sharedOnly,
            @Param("type") String type,
            @Param("status") String status,
            @Param("query") String query,
            @Param("now") Instant now,
            Pageable pageable);

    @Modifying
    @Query("""
            delete from memory_item m
            where m.tenantId = :tenantId and m.userId = :userId
              and (:agentId is null or m.agentId = :agentId)
              and (:personaId is null or m.personaId = :personaId)
              and (:sharedOnly = false or m.personaId is null)
            """)
    int deleteForUser(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("agentId") String agentId,
            @Param("personaId") String personaId,
            @Param("sharedOnly") boolean sharedOnly);
}
