package io.github.yourname.agentstudio.execution;

import org.springframework.data.jpa.repository.JpaRepository;

interface ExecutionSettingsRepository extends JpaRepository<ExecutionSettingsEntity, String> {
}
