package io.github.yourname.cycbercompany.node;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NodeRegistrationTokenRepository extends JpaRepository<NodeRegistrationTokenEntity, String> {

    Optional<NodeRegistrationTokenEntity> findByTokenHash(String tokenHash);
}
