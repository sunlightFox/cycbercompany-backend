package io.github.yourname.agentstudio.model;

import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public model catalog.
 *
 * <p>The catalog stores provider metadata and a credential reference. The raw
 * key stays outside the database, which prevents accidental exposure through
 * API responses, backups, SQL consoles, or logs.
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
        var entity = new ModelProfileEntity(
                command.id(),
                command.providerType(),
                command.baseUrl(),
                command.modelName(),
                command.credentialRef(),
                command.capabilities(),
                command.enabled(),
                Instant.now());
        return ModelProfileView.from(repository.save(entity));
    }
}
