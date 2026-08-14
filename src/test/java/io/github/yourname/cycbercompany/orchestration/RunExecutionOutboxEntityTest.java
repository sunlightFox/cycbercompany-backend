package io.github.yourname.cycbercompany.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RunExecutionOutboxEntityTest {

    @Test
    void processedMessageRequiresItsOwnLease() {
        Instant now = Instant.parse("2026-08-02T00:00:00Z");
        RunExecutionOutboxEntity message = new RunExecutionOutboxEntity("outbox-1", "tenant", "run-1", now);
        assertThat(message.claim("lease-a", now, Duration.ofMinutes(1))).isTrue();

        assertThatThrownBy(() -> message.processed("lease-b", now.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class);
        message.processed("lease-a", now.plusSeconds(1));
        assertThat(message.status()).isEqualTo(RunExecutionOutboxStatus.PROCESSED);
        assertThat(message.processedAt()).isEqualTo(now.plusSeconds(1));
    }

    @Test
    void failedDeliveryReturnsToPendingWithBackoff() {
        Instant now = Instant.parse("2026-08-02T00:00:00Z");
        RunExecutionOutboxEntity message = new RunExecutionOutboxEntity("outbox-1", "tenant", "run-1", now);
        message.claim("lease-a", now, Duration.ofMinutes(1));
        message.retry("lease-a", "temporary failure", now.plusSeconds(4), now.plusSeconds(1));

        assertThat(message.status()).isEqualTo(RunExecutionOutboxStatus.PENDING);
        assertThat(message.availableAt()).isEqualTo(now.plusSeconds(4));
        assertThat(message.lastError()).isEqualTo("temporary failure");
        assertThat(message.claim("lease-b", now.plusSeconds(2), Duration.ofMinutes(1))).isFalse();
        assertThat(message.claim("lease-b", now.plusSeconds(4), Duration.ofMinutes(1))).isTrue();
    }
}
