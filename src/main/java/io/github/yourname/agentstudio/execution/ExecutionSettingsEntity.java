package io.github.yourname.agentstudio.execution;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import java.time.Instant;

/** 租户级执行拓扑设置的数据库实体。 */
@Entity(name = "execution_settings")
class ExecutionSettingsEntity {

    @Id
    /** 租户 ID，同时作为设置表主键。 */
    private String tenantId;

    @Enumerated(EnumType.STRING)
    /** 允许的执行位置模式。 */
    private ExecutionMode mode;

    /** 设置最后修改时间。 */
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
