package io.github.yourname.agentstudio.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yourname.agentstudio.security.ActorContext;
import io.github.yourname.agentstudio.knowledge.KnowledgeEmbeddingService;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemoryRetrievalService {

    private final MemoryItemRepository memories;
    private final ObjectMapper objectMapper;
    private final KnowledgeEmbeddingService embeddings;

    public MemoryRetrievalService(
            MemoryItemRepository memories,
            ObjectMapper objectMapper,
            KnowledgeEmbeddingService embeddings) {
        this.memories = memories;
        this.objectMapper = objectMapper;
        this.embeddings = embeddings;
    }

    @Transactional(readOnly = true)
    public List<MemorySnapshot> retrieve(
            String agentId,
            String personaId,
            String query,
            String memoryPolicyJson,
            ActorContext actor) {
        JsonNode policy = parse(memoryPolicyJson);
        if (!"PERSONALIZED".equals(policy.path("mode").asText())
                || !policy.path("longTerm").path("enabled").asBoolean(false)) {
            return List.of();
        }
        int topK = Math.max(1, Math.min(policy.path("longTerm").path("topK").asInt(3), 12));
        double minRelevance = policy.path("longTerm").path("minRelevance").asDouble(0.0);
        String retrievalMode = policy.path("longTerm").path("retrievalMode").asText("HYBRID");
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        Set<String> queryTerms = terms(normalizedQuery);
        double[] queryVector = "KEYWORD".equals(retrievalMode) || normalizedQuery.isBlank()
                ? null
                : embeddings.embedForSearch(normalizedQuery).orElse(null);
        Instant now = Instant.now();
        return memories.search(
                        actor.tenantId(), actor.userId(), agentId, null, false, null, MemoryStatus.CONFIRMED.name(), null, now,
                        PageRequest.of(0, 100)).stream()
                .filter(item -> item.personaId() == null || item.personaId().equals(personaId))
                .filter(item -> item.expiresAt() == null || item.expiresAt().isAfter(now))
                .map(item -> score(item, normalizedQuery, queryTerms, queryVector, retrievalMode))
                .filter(scored -> normalizedQuery.isBlank() || scored.score() >= minRelevance)
                .sorted(Comparator.comparingDouble(Scored::score).reversed()
                        .thenComparing(scored -> scored.item().importance(), Comparator.reverseOrder())
                        .thenComparing(scored -> scored.item().updatedAt(), Comparator.reverseOrder()))
                .limit(topK)
                .map(scored -> new MemorySnapshot(
                        scored.item().id(),
                        scored.item().type(),
                        scored.item().content(),
                        scored.item().confidence(),
                        scored.item().importance(),
                        scored.item().expiresAt()))
                .toList();
    }

    public List<MemorySnapshot> retrieve(
            String agentId,
            String query,
            String memoryPolicyJson,
            ActorContext actor) {
        return retrieve(agentId, null, query, memoryPolicyJson, actor);
    }

    private static Scored score(
            MemoryItemEntity item,
            String query,
            Set<String> queryTerms,
            double[] queryVector,
            String retrievalMode) {
        double lexical = relevance(item.content(), query, queryTerms);
        double vector = 0.0;
        if (queryVector != null && item.embeddingVector() != null && !item.embeddingVector().isBlank()) {
            try {
                vector = Math.max(0.0, KnowledgeEmbeddingService.cosineSimilarity(
                        queryVector, KnowledgeEmbeddingService.deserialize(item.embeddingVector())));
            } catch (RuntimeException ignored) {
                vector = 0.0;
            }
        }
        double combined = switch (retrievalMode) {
            case "KEYWORD" -> lexical;
            case "SEMANTIC" -> queryVector == null ? lexical : vector;
            default -> queryVector == null ? lexical : 0.45 * lexical + 0.55 * vector;
        };
        return new Scored(item, combined);
    }

    private static double relevance(String content, String query, Set<String> queryTerms) {
        String normalizedContent = content == null ? "" : content.toLowerCase(Locale.ROOT);
        if (query.isBlank()) {
            return 1.0;
        }
        if (normalizedContent.contains(query)) {
            return 1.0;
        }
        if (queryTerms.isEmpty()) {
            return 0.0;
        }
        long matched = queryTerms.stream().filter(normalizedContent::contains).count();
        return (double) matched / queryTerms.size();
    }

    private static Set<String> terms(String query) {
        if (query == null || query.isBlank()) {
            return Set.of();
        }
        return java.util.Arrays.stream(query.split("[^\\p{L}\\p{N}]+"))
                .map(String::trim)
                .filter(value -> value.length() >= 2)
                .collect(Collectors.toUnmodifiableSet());
    }

    private JsonNode parse(String value) {
        try {
            return objectMapper.readTree(value == null || value.isBlank() ? "{}" : value);
        } catch (Exception ex) {
            throw new IllegalStateException("Memory policy snapshot is unreadable.", ex);
        }
    }

    private record Scored(MemoryItemEntity item, double score) {
    }
}
