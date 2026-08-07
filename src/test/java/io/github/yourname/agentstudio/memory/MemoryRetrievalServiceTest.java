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
                        eq(null), eq(MemoryStatus.CONFIRMED.name()), eq(null), any()))
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

    private static MemoryItemEntity memory(String id, String content, String vector, Instant now) {
        return new MemoryItemEntity(
                id,
                "tenant",
                "user",
                "agent",
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
