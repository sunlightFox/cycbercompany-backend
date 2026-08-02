package io.github.yourname.agentstudio.orchestration;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface RunExecutionOutboxRepository extends JpaRepository<RunExecutionOutboxEntity, String> {

    /**
     * 先查询主键而非实体，随后再用 findByIdForUpdate 领取。这样多个 dispatcher 不会
     * 因为已经加载到 JPA 一级缓存的旧对象而误判消息仍可投递。
     */
    @Query("""
            select message.id from run_execution_outbox message
            where (message.status = io.github.yourname.agentstudio.orchestration.RunExecutionOutboxStatus.PENDING
                or (message.status = io.github.yourname.agentstudio.orchestration.RunExecutionOutboxStatus.DISPATCHING
                    and (message.leaseUntil is null or message.leaseUntil <= :now)))
              and (message.availableAt is null or message.availableAt <= :now)
            order by message.createdAt asc
            """)
    List<String> findDispatchableIds(Instant now, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select message from run_execution_outbox message where message.id = :id")
    Optional<RunExecutionOutboxEntity> findByIdForUpdate(String id);
}
