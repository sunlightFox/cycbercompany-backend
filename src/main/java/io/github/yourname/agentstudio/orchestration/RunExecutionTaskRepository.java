package io.github.yourname.agentstudio.orchestration;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface RunExecutionTaskRepository extends JpaRepository<RunExecutionTaskEntity, String> {

    List<RunExecutionTaskEntity> findByStatusInOrderByAvailableAtAsc(Collection<RunExecutionTaskStatus> statuses);

    List<RunExecutionTaskEntity> findByStatusAndLeaseUntilBefore(
            RunExecutionTaskStatus status,
            Instant leaseUntil);

    /**
     * 对单条任务加行锁，缩小锁范围后再更新 lease；配合 @Version 可同时兼容 H2 和生产数据库。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select task from run_execution_task task where task.runId = :runId")
    Optional<RunExecutionTaskEntity> findByRunIdForUpdate(String runId);
}
