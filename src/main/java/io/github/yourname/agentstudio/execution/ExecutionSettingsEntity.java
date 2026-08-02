package io.github.yourname.agentstudio.execution;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import java.time.Instant;

@Entity(name = "execution_settings")
class ExecutionSettingsEntity {

    @Id
    private String tenantId;

    @Enumerated(EnumType.STRING)
    private ExecutionMode mode;

    private Instant updatedAt;

    protected ExecutionSettingsEntity() {
    }

    ExecutionSettingsEntity(String tenantId, ExecutionMode mode, Instant updatedAt) {
        this.tenantId = tenantId;
        this.mode = mode;
        this.updatedAt = updatedAt;
    }

    String tenantId() { return tenantId; }
    ExecutionMode mode() { return mode == null ? ExecutionMode.PERSONAL_LOCAL : mode; }
    Instant updatedAt() { return updatedAt; }

    void update(ExecutionMode mode, Instant updatedAt) {
        this.mode = mode == null ? ExecutionMode.PERSONAL_LOCAL : mode;
        this.updatedAt = updatedAt;
    }
}
