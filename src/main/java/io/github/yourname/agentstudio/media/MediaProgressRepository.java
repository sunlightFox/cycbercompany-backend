package io.github.yourname.agentstudio.media;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MediaProgressRepository extends JpaRepository<MediaProgressEntity, String> {
    Optional<MediaProgressEntity> findByTenantIdAndUserIdAndModIdAndMediaId(
            String tenantId, String userId, String modId, String mediaId);
}
