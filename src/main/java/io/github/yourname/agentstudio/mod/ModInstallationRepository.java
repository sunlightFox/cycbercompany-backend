package io.github.yourname.agentstudio.mod;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ModInstallationRepository extends JpaRepository<ModInstallationEntity, String> {
    boolean existsByTenantIdAndUserIdAndModId(String tenantId, String userId, String modId);
}
