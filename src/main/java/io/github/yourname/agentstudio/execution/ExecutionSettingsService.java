package io.github.yourname.agentstudio.execution;

import io.github.yourname.agentstudio.security.ActorContext;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tenant-scoped execution topology. The personal local mode is the safe default. */
@Service
public class ExecutionSettingsService {

    private final ExecutionSettingsRepository settings;

    public ExecutionSettingsService(ExecutionSettingsRepository settings) {
        this.settings = settings;
    }

    @Transactional(readOnly = true)
    public ExecutionSettingsView get(ActorContext actor) {
        ExecutionSettingsEntity value = settings.findById(actor.tenantId()).orElse(null);
        return value == null
                ? new ExecutionSettingsView(ExecutionMode.PERSONAL_LOCAL, null)
                : new ExecutionSettingsView(value.mode(), value.updatedAt());
    }

    @Transactional(readOnly = true)
    public ExecutionMode mode(ActorContext actor) {
        return settings.findById(actor.tenantId())
                .map(ExecutionSettingsEntity::mode)
                .orElse(ExecutionMode.PERSONAL_LOCAL);
    }

    @Transactional
    public ExecutionSettingsView update(UpdateExecutionSettingsCommand command, ActorContext actor) {
        ExecutionMode mode = command == null || command.mode() == null
                ? ExecutionMode.PERSONAL_LOCAL
                : command.mode();
        Instant now = Instant.now();
        ExecutionSettingsEntity value = settings.findById(actor.tenantId())
                .orElseGet(() -> new ExecutionSettingsEntity(actor.tenantId(), mode, now));
        value.update(mode, now);
        settings.save(value);
        return new ExecutionSettingsView(value.mode(), value.updatedAt());
    }
}
