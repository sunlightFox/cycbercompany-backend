package io.github.yourname.agentstudio.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.yourname.agentstudio.agent.AgentIdentityEntity;
import io.github.yourname.agentstudio.agent.AgentIdentityRepository;
import io.github.yourname.agentstudio.security.ActorContext;
import io.github.yourname.agentstudio.persona.UserPersonaEntity;
import io.github.yourname.agentstudio.persona.UserPersonaRepository;
import io.github.yourname.agentstudio.persona.UserPersonaService;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:memory-integration;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.web-search.enabled=false"
})
class MemoryServiceIntegrationTest {

    @Autowired
    private MemoryService memories;

    @Autowired
    private AgentIdentityRepository identities;

    @Autowired
    private MemoryRetrievalService retrieval;

    @Autowired
    private MemoryCandidateService candidates;

    @Autowired
    private UserPersonaRepository personas;

    @Autowired
    private UserPersonaService personaService;

    @Test
    void userCanGovernMemoryWithinAgentScope() {
        var agent = identities.save(new AgentIdentityEntity(
                "memory-agent-1", "tenant-memory", "user-memory", "Memory Agent", "", "", "", "[]", "TEAM", Instant.now()));
        ActorContext actor = new ActorContext("tenant-memory", "user-memory", java.util.Set.of(), java.util.Set.of());
        MemoryView created = memories.create(
                new CreateMemoryCommand(
                        agent.id(),
                        MemoryType.PROFILE,
                        "用户偏好使用中文回答。",
                        0.8,
                        "conversation-1",
                        "run-1",
                        "用户在会话中明确提出。",
                        null),
                actor);

        assertThat(created.status()).isEqualTo(MemoryStatus.CONFIRMED);
        assertThat(memories.list(agent.id(), MemoryType.PROFILE, null, "中文", 10, actor))
                .extracting(MemoryView::id)
                .containsExactly(created.id());
        MemoryView updated = memories.update(
                created.id(),
                new UpdateMemoryCommand(
                        MemoryType.PROCEDURAL,
                        "回答时优先使用中文。",
                        0.9,
                        null,
                        created.revision()),
                actor);
        assertThat(updated.type()).isEqualTo(MemoryType.PROCEDURAL);
        assertThat(updated.revision()).isGreaterThan(created.revision());
        assertThatThrownBy(() -> memories.update(
                        created.id(),
                        new UpdateMemoryCommand(
                                MemoryType.PROFILE,
                                "过期写入",
                                0.5,
                                null,
                                created.revision()),
                        actor))
                .isInstanceOf(MemoryRevisionConflictException.class);
        memories.delete(created.id(), actor);
        assertThat(memories.list(agent.id(), null, null, null, 10, actor)).isEmpty();
    }

    @Test
    void rejectsCredentialLikeMemoryContent() {
        var agent = identities.save(new AgentIdentityEntity(
                "memory-agent-sensitive", "tenant-memory-sensitive", "user-memory-sensitive",
                "Memory Agent", "", "", "", "[]", "PRIVATE", Instant.now()));
        ActorContext actor = new ActorContext(
                "tenant-memory-sensitive", "user-memory-sensitive", java.util.Set.of(), java.util.Set.of());

        assertThatThrownBy(() -> memories.create(
                        new CreateMemoryCommand(
                                agent.id(), MemoryType.PROFILE, "api_key=sk-test-secret", 0.5,
                                null, null, null, null),
                        actor))
                .hasMessageContaining("credential or payment identifier");
    }

    @Test
    void retrievalUsesConfirmedMemoryRegardlessOfPolicyModeAndRemainsUserScoped() {
        var agent = identities.save(new AgentIdentityEntity(
                "memory-agent-retrieval", "tenant-memory-retrieval", "owner-memory-retrieval",
                "Memory Agent", "", "", "", "[]", "TEAM", Instant.now()));
        ActorContext owner = new ActorContext(
                "tenant-memory-retrieval", "owner-memory-retrieval", java.util.Set.of(), java.util.Set.of());
        ActorContext colleague = new ActorContext(
                "tenant-memory-retrieval", "colleague-memory-retrieval", java.util.Set.of(), java.util.Set.of());
        MemoryView memory = memories.create(
                new CreateMemoryCommand(
                        agent.id(), MemoryType.PROCEDURAL, "Java 测试优先使用 JUnit。", 0.9,
                        null, null, "用户明确说明测试偏好。", null),
                owner);
        String personalized = """
                {"mode":"PERSONALIZED","longTerm":{"enabled":true,"topK":3,"minRelevance":0.2}}
                """;

        assertThat(retrieval.retrieve(agent.id(), "JUnit 测试", personalized, owner))
                .extracting(MemorySnapshot::id)
                .containsExactly(memory.id());
        assertThat(retrieval.retrieve(
                agent.id(), "JUnit 测试", "{\"mode\":\"CONVERSATION\"}", owner))
                .extracting(MemorySnapshot::id)
                .containsExactly(memory.id());
        assertThat(retrieval.retrieve(agent.id(), "JUnit 测试", personalized, colleague)).isEmpty();
    }

    @Test
    void autoExtractedMemoryIsImmediatelyAvailable() {
        var agent = identities.save(new AgentIdentityEntity(
                "memory-agent-candidate", "tenant-memory-candidate", "user-memory-candidate",
                "Memory Agent", "", "", "", "[]", "PRIVATE", Instant.now()));
        ActorContext actor = new ActorContext(
                "tenant-memory-candidate", "user-memory-candidate", java.util.Set.of(), java.util.Set.of());
        String policy = """
                {"mode":"PERSONALIZED","longTerm":{"enabled":true,"categories":["PROCEDURAL"],
                "writeMode":"SUGGEST","topK":3,"minRelevance":0.1,"ttlDays":30}}
                """;

        MemoryView candidate = candidates.capture(
                        agent.id(), "conversation-1", "run-1", "以后请始终用中文回答。", policy, actor)
                .orElseThrow();
        assertThat(candidate.status()).isEqualTo(MemoryStatus.CONFIRMED);
        assertThat(candidate.origin()).isEqualTo(MemoryOrigin.AUTO_EXTRACTED);
        assertThat(candidate.type()).isEqualTo(MemoryType.PROCEDURAL);
        assertThat(candidate.expiresAt()).isAfter(Instant.now());
        assertThat(candidates.capture(
                agent.id(), "conversation-2", "run-2", "以后请始终用中文回答。", policy, actor)).isEmpty();
        assertThat(retrieval.retrieve(agent.id(), "中文回答", policy, actor))
                .extracting(MemorySnapshot::id)
                .containsExactly(candidate.id());

        MemoryView rejected = memories.reject(candidate.id(), actor);
        assertThat(rejected.status()).isEqualTo(MemoryStatus.REJECTED);
        assertThat(retrieval.retrieve(agent.id(), "中文回答", policy, actor)).isEmpty();
    }

    @Test
    void userCreatedAgentMemoryWinsOverConflictingAutomaticMemory() {
        var agent = identities.save(new AgentIdentityEntity(
                "memory-agent-priority", "tenant-memory-priority", "user-memory-priority",
                "Memory Agent", "", "", "", "[]", "PRIVATE", Instant.now()));
        ActorContext actor = new ActorContext(
                "tenant-memory-priority", "user-memory-priority", java.util.Set.of(), java.util.Set.of());
        MemoryView manual = memories.create(new CreateMemoryCommand(
                agent.id(), MemoryType.PROFILE, "The agent likes red.", 0.9,
                null, null, null, null, null, MemoryScope.AGENT), actor);
        String policy = """
                {"longTerm":{"categories":["PROFILE"],"writeMode":"SUGGEST","topK":3}}
                """;

        assertThat(candidates.capture(
                agent.id(), "conversation-priority", "run-priority", "agent likes blue", policy, actor)).isEmpty();
        assertThat(retrieval.retrieve(agent.id(), "", policy, actor))
                .extracting(MemorySnapshot::id)
                .contains(manual.id());
        assertThat(memories.list(agent.id(), null, false, null, null, MemoryOrigin.USER_CREATED, null, 10, actor))
                .extracting(MemoryView::id)
                .containsExactly(manual.id());
    }

    @Test
    void candidateDeduplicationIsScopedToPersona() {
        var agent = identities.save(new AgentIdentityEntity(
                "memory-agent-candidate-persona", "tenant-memory-candidate-persona", "user-memory-candidate-persona",
                "Memory Agent", "", "", "", "[]", "PRIVATE", Instant.now()));
        Instant now = Instant.now();
        var work = personas.save(new UserPersonaEntity(
                "candidate-persona-work", "tenant-memory-candidate-persona", "user-memory-candidate-persona",
                "Work", "", "{}", true, now));
        var personal = personas.save(new UserPersonaEntity(
                "candidate-persona-personal", "tenant-memory-candidate-persona", "user-memory-candidate-persona",
                "Personal", "", "{}", false, now));
        ActorContext actor = new ActorContext(
                "tenant-memory-candidate-persona", "user-memory-candidate-persona",
                java.util.Set.of(), java.util.Set.of());
        String policy = """
                {"mode":"PERSONALIZED","longTerm":{"enabled":true,"categories":["PROCEDURAL"],
                "writeMode":"SUGGEST","topK":3,"minRelevance":0.1}}
                """;
        String preference = "以后请始终使用简洁回答。";

        MemoryView workCandidate = candidates.capture(
                agent.id(), work.id(), "conversation-work", "run-work", preference, policy, actor).orElseThrow();
        assertThat(candidates.capture(
                agent.id(), work.id(), "conversation-work-2", "run-work-2", preference, policy, actor)).isEmpty();
        MemoryView personalCandidate = candidates.capture(
                agent.id(), personal.id(), "conversation-personal", "run-personal", preference, policy, actor).orElseThrow();
        MemoryView sharedCandidate = candidates.capture(
                agent.id(), null, "conversation-shared", "run-shared", preference, policy, actor).orElseThrow();

        assertThat(workCandidate.personaId()).isEqualTo(work.id());
        assertThat(personalCandidate.personaId()).isEqualTo(personal.id());
        assertThat(sharedCandidate.personaId()).isNull();
    }

    @Test
    void personaScopedRecallIncludesGlobalButExcludesOtherPersonaMemories() {
        var agent = identities.save(new AgentIdentityEntity(
                "memory-agent-persona", "tenant-memory-persona", "user-memory-persona",
                "Memory Agent", "", "", "", "[]", "PRIVATE", Instant.now()));
        Instant now = Instant.now();
        var work = personas.save(new UserPersonaEntity(
                "persona-work", "tenant-memory-persona", "user-memory-persona",
                "Work", "", "{}", true, now));
        var personal = personas.save(new UserPersonaEntity(
                "persona-personal", "tenant-memory-persona", "user-memory-persona",
                "Personal", "", "{}", false, now));
        ActorContext actor = new ActorContext(
                "tenant-memory-persona", "user-memory-persona", java.util.Set.of(), java.util.Set.of());
        MemoryView global = memories.create(new CreateMemoryCommand(
                agent.id(), MemoryType.PROCEDURAL, "Global response preference.", 0.7,
                null, null, null, null), actor);
        MemoryView workMemory = memories.create(new CreateMemoryCommand(
                agent.id(), MemoryType.PROCEDURAL, "Work context preference.", 0.7,
                null, null, null, null, work.id()), actor);
        MemoryView personalMemory = memories.create(new CreateMemoryCommand(
                agent.id(), MemoryType.PROCEDURAL, "Personal context preference.", 0.7,
                null, null, null, null, personal.id()), actor);
        String policy = """
                {"mode":"PERSONALIZED","longTerm":{"enabled":true,"topK":5,
                "minRelevance":0.1,"retrievalMode":"KEYWORD"}}
                """;

        assertThat(retrieval.retrieve(agent.id(), work.id(), "context preference", policy, actor))
                .extracting(MemorySnapshot::id)
                .containsExactlyInAnyOrder(global.id(), workMemory.id())
                .doesNotContain(personalMemory.id());
        assertThat(retrieval.retrieve(agent.id(), personal.id(), "context preference", policy, actor))
                .extracting(MemorySnapshot::id)
                .containsExactlyInAnyOrder(global.id(), personalMemory.id())
                .doesNotContain(workMemory.id());

        assertThat(memories.list(agent.id(), null, true, null, null, null, 10, actor))
                .extracting(MemoryView::id)
                .containsExactly(global.id());

        assertThat(memories.clear(new ClearMemoryCommand(agent.id(), work.id()), actor)).isEqualTo(1);
        assertThat(memories.list(agent.id(), work.id(), null, null, null, 10, actor)).isEmpty();
        assertThat(memories.list(agent.id(), null, null, null, null, 10, actor))
                .extracting(MemoryView::id)
                .containsExactlyInAnyOrder(global.id(), personalMemory.id());
        assertThat(memories.clear(new ClearMemoryCommand(agent.id(), null, true), actor)).isEqualTo(1);
        assertThat(memories.list(agent.id(), null, null, null, null, 10, actor))
                .extracting(MemoryView::id)
                .containsExactly(personalMemory.id());
    }

    @Test
    void expiredMemoriesDoNotConsumeListLimit() {
        var agent = identities.save(new AgentIdentityEntity(
                "memory-agent-expiry", "tenant-memory-expiry", "user-memory-expiry",
                "Memory Agent", "", "", "", "[]", "PRIVATE", Instant.now()));
        ActorContext actor = new ActorContext(
                "tenant-memory-expiry", "user-memory-expiry", java.util.Set.of(), java.util.Set.of());
        memories.create(new CreateMemoryCommand(
                agent.id(), MemoryType.PROFILE, "Expired preference.", 0.5,
                null, null, null, Instant.now().minusSeconds(60), null), actor);
        MemoryView active = memories.create(new CreateMemoryCommand(
                agent.id(), MemoryType.PROFILE, "Active preference.", 0.5,
                null, null, null, Instant.now().plusSeconds(60), null), actor);

        assertThat(memories.list(agent.id(), null, null, null, null, 1, actor))
                .extracting(MemoryView::id)
                .containsExactly(active.id());
    }

    @Test
    void deletingPersonaRemovesOnlyItsScopedMemories() {
        var agent = identities.save(new AgentIdentityEntity(
                "memory-agent-persona-delete", "tenant-memory-persona-delete", "user-memory-persona-delete",
                "Memory Agent", "", "", "", "[]", "PRIVATE", Instant.now()));
        var persona = personas.save(new UserPersonaEntity(
                "persona-delete", "tenant-memory-persona-delete", "user-memory-persona-delete",
                "Temporary", "", "{}", true, Instant.now()));
        ActorContext actor = new ActorContext(
                "tenant-memory-persona-delete", "user-memory-persona-delete",
                java.util.Set.of(), java.util.Set.of());
        MemoryView shared = memories.create(new CreateMemoryCommand(
                agent.id(), MemoryType.PROFILE, "Shared preference.", 0.5,
                null, null, null, null, null), actor);
        memories.create(new CreateMemoryCommand(
                agent.id(), MemoryType.PROFILE, "Persona preference.", 0.5,
                null, null, null, null, persona.id()), actor);

        personaService.delete(persona.id(), actor);

        assertThat(personas.findById(persona.id())).isEmpty();
        assertThat(memories.list(agent.id(), null, null, null, null, 10, actor))
                .extracting(MemoryView::id)
                .containsExactly(shared.id());
    }
}
