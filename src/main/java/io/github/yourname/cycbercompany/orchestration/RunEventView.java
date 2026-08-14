package io.github.yourname.cycbercompany.orchestration;

import java.time.Instant;

public record RunEventView(long sequence, RunEventType type, String payload, Instant createdAt) {
    static RunEventView from(RunEventEntity entity) {
        return new RunEventView(entity.sequence(), entity.type(), entity.payload(), entity.createdAt());
    }
}
