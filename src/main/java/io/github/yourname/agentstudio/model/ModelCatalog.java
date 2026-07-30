package io.github.yourname.agentstudio.model;

import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public model catalog.
 *
 * <p>The catalog stores provider metadata and, when configured from the studio
 * UI, the provider API key. API responses never expose the raw key; callers see
 * only whether a key exists and a short masked preview for review.
 */
@Service
public class ModelCatalog {

    private final ModelProfileRepository repository;

    public ModelCatalog(ModelProfileRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<ModelProfileView> list() {
        return repository.findAll().stream().map(ModelProfileView::from).toList();
    }

    @Transactional
    public ModelProfileView save(UpsertModelProfileCommand command) {
        var entity = repository.findById(command.id())
                .orElseGet(() -> new ModelProfileEntity(
                        command.id(),
                        command.providerType(),
                        command.baseUrl(),
                        command.modelName(),
                        command.credentialRef(),
                        command.apiKey(),
                        command.capabilities(),
                        command.enabled(),
                        Instant.now()));
        entity.update(
                command.providerType(),
                command.baseUrl(),
                command.modelName(),
                command.credentialRef(),
                command.apiKey(),
                command.capabilities(),
                command.enabled());
        return ModelProfileView.from(repository.save(entity));
    }
}
