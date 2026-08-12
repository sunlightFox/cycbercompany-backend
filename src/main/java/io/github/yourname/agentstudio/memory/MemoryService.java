package io.github.yourname.agentstudio.memory;

import io.github.yourname.agentstudio.agent.AgentIdentityEntity;
import io.github.yourname.agentstudio.agent.AgentIdentityRepository;
import io.github.yourname.agentstudio.knowledge.KnowledgeEmbeddingService;
import io.github.yourname.agentstudio.persona.UserPersonaRepository;
import io.github.yourname.agentstudio.security.ActorContext;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemoryService {

    private final MemoryItemRepository memories;
    private final AgentIdentityRepository identities;
    private final MemorySafetyPolicy safety;
    private final KnowledgeEmbeddingService embeddings;
    private final UserPersonaRepository personas;

    public MemoryService(
            MemoryItemRepository memories,
            AgentIdentityRepository identities,
            MemorySafetyPolicy safety,
            KnowledgeEmbeddingService embeddings,
            UserPersonaRepository personas) {
        this.memories = memories;
        this.identities = identities;
        this.safety = safety;
        this.embeddings = embeddings;
        this.personas = personas;
    }

    @Transactional(readOnly = true)
    public List<MemoryView> list(
            String agentId,
            String personaId,
            MemoryType type,
            MemoryStatus status,
            String query,
            int limit,
            ActorContext actor) {
        return list(agentId, personaId, false, type, status, null, query, limit, actor);
    }

    @Transactional(readOnly = true)
    public List<MemoryView> list(
            String agentId,
            String personaId,
            boolean sharedOnly,
            MemoryType type,
            MemoryStatus status,
            MemoryOrigin origin,
            String query,
            int limit,
            ActorContext actor) {
        return list(agentId, personaId, sharedOnly, type, status, query, limit, actor).stream()
                .filter(memory -> origin == null || memory.origin() == origin)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MemoryView> list(
            String agentId,
            String personaId,
            boolean sharedOnly,
            MemoryType type,
            MemoryStatus status,
            String query,
            int limit,
            ActorContext actor) {
        if (agentId != null && !agentId.isBlank()) {
            requireVisibleAgent(agentId, actor);
        }
        if (sharedOnly && personaId != null && !personaId.isBlank()) {
            throw new IllegalArgumentException("Shared memory scope cannot target a persona.");
        }
        if (!sharedOnly && personaId != null && !personaId.isBlank()) {
            requirePersona(personaId, actor);
        }
        int boundedLimit = Math.max(1, Math.min(limit <= 0 ? 50 : limit, 100));
        String normalizedQuery = query == null ? null : query.trim();
        Instant now = Instant.now();
        return memories.search(
                        actor.tenantId(),
                        actor.userId(),
                        blankToNull(agentId),
                        blankToNull(personaId),
                        sharedOnly,
                        type == null ? null : type.name(),
                        status == null ? null : status.name(),
                        normalizedQuery,
                        now,
                        PageRequest.of(0, boundedLimit)).stream()
                .map(MemoryView::from)
                .toList();
    }

    public List<MemoryView> list(
            String agentId,
            MemoryType type,
            MemoryStatus status,
            String query,
            int limit,
            ActorContext actor) {
        return list(agentId, null, type, status, query, limit, actor);
    }

    @Transactional
    public MemoryView create(CreateMemoryCommand command, ActorContext actor) {
        requireVisibleAgent(command.agentId(), actor);
        MemoryScope scope = command.scope() == null ? MemoryScope.AGENT : command.scope();
        if (scope == MemoryScope.AGENT && command.personaId() != null && !command.personaId().isBlank()) {
            throw new IllegalArgumentException("Agent memory cannot target a user persona.");
        }
        if (scope == MemoryScope.USER && command.personaId() != null && !command.personaId().isBlank()) {
            requirePersona(command.personaId(), actor);
        }
        safety.validateUserMemory(command.content());
        Instant now = Instant.now();
        MemoryItemEntity item = new MemoryItemEntity(
                UUID.randomUUID().toString(),
                actor.tenantId(),
                actor.userId(),
                command.agentId(),
                scope,
                MemoryOrigin.USER_CREATED,
                MemoryKey.infer(scope, command.type(), command.content()),
                blankToNull(command.personaId()),
                command.type(),
                MemoryStatus.CONFIRMED,
                MemorySensitivity.NORMAL,
                command.content().trim(),
                1.0,
                command.importance() == null ? 0.5 : command.importance(),
                command.sourceConversationId(),
                command.sourceRunId(),
                command.evidenceSummary(),
                embeddings.embedForStorage(command.content().trim()).orElse(null),
                now,
                command.expiresAt());
        return MemoryView.from(memories.save(item));
    }

    @Transactional
    public MemoryView update(String id, UpdateMemoryCommand command, ActorContext actor) {
        MemoryItemEntity item = requireOwned(id, actor);
        if (command.expectedRevision() != item.revision()) {
            throw new MemoryRevisionConflictException(id, command.expectedRevision(), item.revision());
        }
        safety.validateUserMemory(command.content());
        MemoryScope scope = command.scope() == null ? item.scope() : command.scope();
        if (scope == MemoryScope.AGENT && command.personaId() != null && !command.personaId().isBlank()) {
            throw new IllegalArgumentException("Agent memory cannot target a user persona.");
        }
        if (scope == MemoryScope.USER && command.personaId() != null && !command.personaId().isBlank()) {
            requirePersona(command.personaId(), actor);
        }
        item.revise(
                command.type(),
                command.content().trim(),
                command.importance() == null ? item.importance() : command.importance(),
                command.expiresAt(),
                embeddings.embedForStorage(command.content().trim()).orElse(null),
                Instant.now());
        item.changeScope(
                scope,
                MemoryKey.infer(scope, command.type(), command.content()),
                scope == MemoryScope.AGENT ? null : blankToNull(command.personaId()),
                Instant.now());
        return MemoryView.from(memories.saveAndFlush(item));
    }

    @Transactional
    public MemoryView confirm(String id, ActorContext actor) {
        MemoryItemEntity item = requireOwned(id, actor);
        item.confirm(Instant.now());
        return MemoryView.from(memories.saveAndFlush(item));
    }

    @Transactional
    public MemoryView reject(String id, ActorContext actor) {
        MemoryItemEntity item = requireOwned(id, actor);
        item.reject(Instant.now());
        return MemoryView.from(memories.saveAndFlush(item));
    }

    @Transactional
    public void delete(String id, ActorContext actor) {
        MemoryItemEntity item = requireOwned(id, actor);
        memories.delete(item);
    }

    @Transactional
    public int clear(ClearMemoryCommand command, ActorContext actor) {
        if (command.agentId() != null && !command.agentId().isBlank()) {
            requireVisibleAgent(command.agentId(), actor);
        }
        if (command.sharedOnly() && command.personaId() != null && !command.personaId().isBlank()) {
            throw new IllegalArgumentException("Shared memory scope cannot target a persona.");
        }
        if (!command.sharedOnly() && command.personaId() != null && !command.personaId().isBlank()) {
            requirePersona(command.personaId(), actor);
        }
        return memories.deleteForUser(
                actor.tenantId(), actor.userId(), blankToNull(command.agentId()), blankToNull(command.personaId()), command.sharedOnly());
    }

    private MemoryItemEntity requireOwned(String id, ActorContext actor) {
        return memories.findByIdAndTenantIdAndUserId(id, actor.tenantId(), actor.userId())
                .orElseThrow(() -> new IllegalArgumentException("Memory not found: " + id));
    }

    private AgentIdentityEntity requireVisibleAgent(String agentId, ActorContext actor) {
        AgentIdentityEntity identity = identities.findByIdAndTenantId(agentId, actor.tenantId())
                .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentId));
        if ("ARCHIVED".equals(identity.status())
                || ("PRIVATE".equals(identity.visibility()) && !identity.ownerUserId().equals(actor.userId()))) {
            throw new IllegalArgumentException("Agent not found: " + agentId);
        }
        return identity;
    }

    private void requirePersona(String personaId, ActorContext actor) {
        personas.findByIdAndTenantIdAndUserId(personaId, actor.tenantId(), actor.userId())
                .orElseThrow(() -> new IllegalArgumentException("User persona not found: " + personaId));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
