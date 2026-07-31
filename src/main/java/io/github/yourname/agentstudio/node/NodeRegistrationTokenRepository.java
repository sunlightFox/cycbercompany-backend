package io.github.yourname.agentstudio.node;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NodeRegistrationTokenRepository extends JpaRepository<NodeRegistrationTokenEntity, String> {

    Optional<NodeRegistrationTokenEntity> findByTenantIdAndTokenHash(String tenantId, String tokenHash);
}
