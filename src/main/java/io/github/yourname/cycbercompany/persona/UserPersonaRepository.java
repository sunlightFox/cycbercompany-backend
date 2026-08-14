package io.github.yourname.cycbercompany.persona;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserPersonaRepository extends JpaRepository<UserPersonaEntity, String> {
    Optional<UserPersonaEntity> findByIdAndTenantIdAndUserId(String id, String tenantId, String userId);
    List<UserPersonaEntity> findAllByTenantIdAndUserIdOrderByUpdatedAtDesc(String tenantId, String userId);
    boolean existsByTenantIdAndUserId(String tenantId, String userId);
    Optional<UserPersonaEntity> findByTenantIdAndUserIdAndNameIgnoreCase(String tenantId, String userId, String name);
    Optional<UserPersonaEntity> findFirstByTenantIdAndUserIdAndDefaultPersonaTrue(
            String tenantId, String userId);
}
