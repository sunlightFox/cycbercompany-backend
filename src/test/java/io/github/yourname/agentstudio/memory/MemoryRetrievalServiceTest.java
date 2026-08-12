package io.github.yourname.agentstudio.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yourname.agentstudio.knowledge.KnowledgeEmbeddingService;
import io.github.yourname.agentstudio.security.ActorContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MemoryRetrievalServiceTest {

    @Test
    void retrievalModeControlsKeywordSemanticAndHybridRanking() {
        MemoryItemRepository repository = mock(MemoryItemRepository.class);
        KnowledgeEmbeddingService embeddings = mock(KnowledgeEmbeddingService.class);
        MemoryRetrievalService service = new MemoryRetrievalService(
                repository, new ObjectMapper(), embeddings);
        Instant now = Instant.now();
        MemoryItemEntity semantic = memory(
                "semantic", "Prefers concise release notes.", "1.0,0.0", now.plusSeconds(1));
        MemoryItemEntity lexical = memory(
                "lexical", "Uses JUnit for project tests.", "0.0,1.0", now);
        when(repository.search(
                        eq("tenant"), eq("user"), eq("agent"), eq(null),
                        eq(false), eq(null), eq(MemoryStatus.CONFIRMED.name()), eq(null), any(Instant.class), any()))
                .thenReturn(List.of(lexical, semantic));
        when(embeddings.embedForSearch("junit tests")).thenReturn(Optional.of(new double[] {1, 0}));
        ActorContext actor = new ActorContext("tenant", "user", Set.of(), Set.of());

        assertThat(service.retrieve("agent", "JUnit tests", policy("KEYWORD"), actor))
                .extracting(MemorySnapshot::id)
                .containsExactly("lexical");
        assertThat(service.retrieve("agent", "JUnit tests", policy("SEMANTIC"), actor))
                .extracting(MemorySnapshot::id)
                .containsExactly("semantic");
        assertThat(service.retrieve("agent", "JUnit tests", policy("HYBRID"), actor))
                .extracting(MemorySnapshot::id)
                .containsExactly("semantic", "lexical");
    }

    @Test
    void defaultPolicyStillRetrievesConfirmedMemory() {
        MemoryItemRepository repository = mock(MemoryItemRepository.class);
        KnowledgeEmbeddingService embeddings = mock(KnowledgeEmbeddingService.class);
        MemoryRetrievalService service = new MemoryRetrievalService(
                repository, new ObjectMapper(), embeddings);
        Instant now = Instant.now();
        MemoryItemEntity preference = memory("preference", "The agent prefers red.", null, now);
        when(repository.search(
                        eq("tenant"), eq("user"), eq("agent"), eq(null),
                        eq(false), eq(null), eq(MemoryStatus.CONFIRMED.name()), eq(null), any(Instant.class), any()))
                .thenReturn(List.of(preference));
        when(embeddings.embedForSearch("what color do you like?")).thenReturn(Optional.empty());
        ActorContext actor = new ActorContext("tenant", "user", Set.of(), Set.of());

        assertThat(service.retrieve("agent", "What color do you like?", "{\"mode\":\"CONVERSATION\"}", actor))
                .extracting(MemorySnapshot::id)
                .containsExactly("preference");
    }

    @Test
    void agentMemoryIsRecalledForAnyUserPersona() {
        MemoryItemRepository repository = mock(MemoryItemRepository.class);
        KnowledgeEmbeddingService embeddings = mock(KnowledgeEmbeddingService.class);
        MemoryRetrievalService service = new MemoryRetrievalService(repository, new ObjectMapper(), embeddings);
        Instant now = Instant.now();
        MemoryItemEntity preference = new MemoryItemEntity(
                "agent-preference", "tenant", "creator", "agent", MemoryScope.AGENT, null,
                MemoryType.PROFILE, MemoryStatus.CONFIRMED, MemorySensitivity.NORMAL,
                "The agent prefers red.", 1.0, 0.9, null, null, null, null, now, null);
        when(repository.search(eq("tenant"), eq("user"), eq("agent"), eq(null), eq(false), eq(null),
                eq(MemoryStatus.CONFIRMED.name()), eq(null), any(Instant.class), any())).thenReturn(List.of());
        when(repository.findActiveAgentMemories(eq("tenant"), eq("agent"), eq(MemoryStatus.CONFIRMED.name()),
                any(Instant.class), any())).thenReturn(List.of(preference));
        when(embeddings.embedForSearch("what color do you like?")).thenReturn(Optional.empty());

        assertThat(service.retrieve("agent", "persona-a", "What color do you like?", "{}",
                new ActorContext("tenant", "user", Set.of(), Set.of())))
                .extracting(MemorySnapshot::id)
                .containsExactly("agent-preference");
    }

    private static MemoryItemEntity memory(String id, String content, String vector, Instant now) {
        return new MemoryItemEntity(
                id,
                "tenant",
                "user",
                "agent",
                MemoryScope.USER,
                null,
                MemoryType.PROCEDURAL,
                MemoryStatus.CONFIRMED,
                MemorySensitivity.NORMAL,
                content,
                1.0,
                0.8,
                null,
                null,
                "evidence",
                vector,
                now,
                null);
    }

    private static String policy(String retrievalMode) {
        return "{\"mode\":\"PERSONALIZED\",\"longTerm\":{\"enabled\":true,\"topK\":3,"
                + "\"minRelevance\":0.1,\"retrievalMode\":\"" + retrievalMode + "\"}}";
    }
}
