package io.github.yourname.agentstudio.persona;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yourname.agentstudio.security.ActorContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserPersonaService {

    private static final int MAX_ATTRIBUTES_JSON_LENGTH = 16000;

    private final UserPersonaRepository personas;
    private final ObjectMapper objectMapper;

    public UserPersonaService(UserPersonaRepository personas, ObjectMapper objectMapper) {
        this.personas = personas;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<UserPersonaView> list(ActorContext actor) {
        return personas.findAllByTenantIdAndUserIdOrderByUpdatedAtDesc(actor.tenantId(), actor.userId()).stream()
                .map(this::view)
                .toList();
    }

    @Transactional(readOnly = true)
    public UserPersonaView get(String id, ActorContext actor) {
        return view(requireOwned(id, actor));
    }

    @Transactional
    public UserPersonaView create(CreateUserPersonaCommand command, ActorContext actor) {
        String name = command.name().trim();
        rejectDuplicateName(null, name, actor);
        boolean makeDefault = Boolean.TRUE.equals(command.defaultPersona())
                || !personas.existsByTenantIdAndUserId(actor.tenantId(), actor.userId());
        if (makeDefault) {
            clearDefault(actor, Instant.now());
        }
        Instant now = Instant.now();
        UserPersonaEntity entity = new UserPersonaEntity(
                UUID.randomUUID().toString(),
                actor.tenantId(),
                actor.userId(),
                name,
                blank(command.description()),
                serializeAttributes(command.attributes()),
                makeDefault,
                now);
        return view(personas.save(entity));
    }

    @Transactional
    public UserPersonaView update(String id, UpdateUserPersonaCommand command, ActorContext actor) {
        UserPersonaEntity entity = requireOwned(id, actor);
        if (command.expectedRevision() != entity.revision()) {
            throw new UserPersonaRevisionConflictException(id, command.expectedRevision(), entity.revision());
        }
        String name = command.name().trim();
        rejectDuplicateName(id, name, actor);
        entity.update(
                name,
                blank(command.description()),
                serializeAttributes(command.attributes()),
                Instant.now());
        return view(personas.saveAndFlush(entity));
    }

    @Transactional
    public UserPersonaView setDefault(String id, ActorContext actor) {
        UserPersonaEntity target = requireOwned(id, actor);
        Instant now = Instant.now();
        clearDefault(actor, now);
        target.setDefaultPersona(true, now);
        return view(personas.saveAndFlush(target));
    }

    @Transactional
    public void delete(String id, ActorContext actor) {
        UserPersonaEntity target = requireOwned(id, actor);
        boolean wasDefault = target.defaultPersona();
        personas.delete(target);
        personas.flush();
        if (wasDefault) {
            personas.findAllByTenantIdAndUserIdOrderByUpdatedAtDesc(actor.tenantId(), actor.userId()).stream()
                    .findFirst()
                    .ifPresent(replacement -> {
                        replacement.setDefaultPersona(true, Instant.now());
                        personas.save(replacement);
                    });
        }
    }

    private void clearDefault(ActorContext actor, Instant now) {
        List<UserPersonaEntity> values = personas.findAllByTenantIdAndUserIdOrderByUpdatedAtDesc(
                actor.tenantId(), actor.userId());
        values.stream().filter(UserPersonaEntity::defaultPersona)
                .forEach(value -> value.setDefaultPersona(false, now));
        if (!values.isEmpty()) {
            personas.saveAll(values);
        }
    }

    private void rejectDuplicateName(String currentId, String name, ActorContext actor) {
        personas.findByTenantIdAndUserIdAndNameIgnoreCase(actor.tenantId(), actor.userId(), name)
                .filter(existing -> !existing.id().equals(currentId))
                .ifPresent(existing -> {
                    throw new IllegalArgumentException("A user persona with this name already exists: " + name);
                });
    }

    private UserPersonaEntity requireOwned(String id, ActorContext actor) {
        return personas.findByIdAndTenantIdAndUserId(id, actor.tenantId(), actor.userId())
                .orElseThrow(() -> new IllegalArgumentException("User persona not found: " + id));
    }

    private UserPersonaView view(UserPersonaEntity entity) {
        return new UserPersonaView(
                entity.id(), entity.name(), entity.description(), parseAttributes(entity.attributesJson()),
                entity.defaultPersona(), entity.revision(), entity.createdAt(), entity.updatedAt());
    }

    private String serializeAttributes(Map<String, Object> attributes) {
        try {
            String json = objectMapper.writeValueAsString(attributes == null ? Map.of() : attributes);
            if (json.length() > MAX_ATTRIBUTES_JSON_LENGTH) {
                throw new IllegalArgumentException("User persona attributes are too large.");
            }
            return json;
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("User persona attributes must be valid JSON values.", ex);
        }
    }

    private Map<String, Object> parseAttributes(String json) {
        try {
            return objectMapper.readValue(json == null || json.isBlank() ? "{}" : json, new TypeReference<>() { });
        } catch (Exception ex) {
            throw new IllegalStateException("Stored user persona attributes are unreadable.", ex);
        }
    }

    private static String blank(String value) {
        return value == null ? "" : value.trim();
    }
}
