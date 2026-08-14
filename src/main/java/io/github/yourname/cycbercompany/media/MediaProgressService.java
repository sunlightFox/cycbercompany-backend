package io.github.yourname.cycbercompany.media;

import io.github.yourname.cycbercompany.security.ActorContext;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MediaProgressService {

    private final MediaProgressRepository repository;

    public MediaProgressService(MediaProgressRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public MediaProgressView get(String modId, String mediaId, ActorContext actor) {
        return repository.findByTenantIdAndUserIdAndModIdAndMediaId(
                        actor.tenantId(), actor.userId(), modId, mediaId)
                .map(MediaProgressService::view)
                .orElse(null);
    }

    @Transactional
    public MediaProgressView save(MediaProgressCommand command, ActorContext actor) {
        Instant now = Instant.now();
        long position = nonNegative(command.positionMs());
        long duration = nonNegative(command.durationMs());
        boolean completed = Boolean.TRUE.equals(command.completed()) || (duration > 0 && position >= duration);
        var existing = repository.findByTenantIdAndUserIdAndModIdAndMediaId(
                actor.tenantId(), actor.userId(), command.modId(), command.mediaId());
        MediaProgressEntity entity;
        if (existing.isPresent()) {
            entity = existing.get();
            entity.update(command.sourceId(), command.episodeId(), position, duration, completed, now);
        } else {
            entity = new MediaProgressEntity(
                    "progress_" + UUID.randomUUID(), actor.tenantId(), actor.userId(), command.modId(),
                    command.mediaId(), command.sourceId(), command.episodeId(), position, duration, completed, now);
        }
        return view(repository.save(entity));
    }

    private static long nonNegative(Long value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private static MediaProgressView view(MediaProgressEntity entity) {
        return new MediaProgressView(entity.modId(), entity.mediaId(), entity.sourceId(), entity.episodeId(),
                entity.positionMs(), entity.durationMs(), entity.completed(), entity.updatedAt());
    }
}
