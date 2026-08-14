package io.github.yourname.cycbercompany.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 纯状态机测试：不启动 Spring，先把最容易出错的租约边界固定下来。 */
class RunExecutionTaskEntityTest {

    @Test
    void sameTaskCannotBeClaimedTwiceUntilLeaseExpires() {
        Instant created = Instant.parse("2026-08-02T00:00:00Z");
        RunExecutionTaskEntity task = new RunExecutionTaskEntity("run-1", "tenant", "conversation", created);

        assertThat(task.claim("lease-a", created.plusSeconds(1), Duration.ofSeconds(30))).isTrue();
        assertThat(task.claim("lease-b", created.plusSeconds(2), Duration.ofSeconds(30))).isFalse();
        assertThat(task.attempt()).isEqualTo(1);
        assertThat(task.leaseId()).isEqualTo("lease-a");

        assertThat(task.claim("lease-b", created.plusSeconds(32), Duration.ofSeconds(30))).isTrue();
        assertThat(task.attempt()).isEqualTo(2);
        assertThat(task.leaseId()).isEqualTo("lease-b");
    }

    @Test
    void approvalPauseClearsLeaseAndReadyCanResumeLater() {
        Instant now = Instant.parse("2026-08-02T00:00:00Z");
        RunExecutionTaskEntity task = new RunExecutionTaskEntity("run-1", "tenant", "conversation", now);
        task.claim("lease-a", now, Duration.ofMinutes(5));

        task.waitForApproval(now.plusSeconds(10));
        assertThat(task.status()).isEqualTo(RunExecutionTaskStatus.WAITING_APPROVAL);
        assertThat(task.leaseId()).isNull();
        assertThat(task.leaseUntil()).isNull();

        task.ready(now.plusSeconds(20));
        assertThat(task.status()).isEqualTo(RunExecutionTaskStatus.READY);
        assertThat(task.claim("lease-b", now.plusSeconds(21), Duration.ofMinutes(5))).isTrue();
    }

    @Test
    void unknownIsTerminalAndCannotBeAutomaticallyReplayed() {
        Instant now = Instant.parse("2026-08-02T00:00:00Z");
        RunExecutionTaskEntity task = new RunExecutionTaskEntity("run-1", "tenant", "conversation", now);
        task.claim("lease-a", now, Duration.ofMinutes(5));
        task.markUnknown("node outcome cannot be reconciled", now.plusSeconds(360));

        assertThat(task.status()).isEqualTo(RunExecutionTaskStatus.UNKNOWN);
        assertThat(task.lastError()).contains("cannot be reconciled");
        assertThat(task.claim("lease-b", now.plusSeconds(420), Duration.ofMinutes(5))).isFalse();
    }
}
