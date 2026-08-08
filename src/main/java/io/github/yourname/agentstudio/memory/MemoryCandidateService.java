package io.github.yourname.agentstudio.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yourname.agentstudio.security.ActorContext;
import io.github.yourname.agentstudio.knowledge.KnowledgeEmbeddingService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

@Service
public class MemoryCandidateService {

    private static final Pattern EXPLICIT = Pattern.compile(
            "(?is)(?:请)?记住[：:，,\\s]*(.{2,500})|remember(?:\\s+that)?[：:，,\\s]+(.{2,500})");
    private static final Pattern PREFERENCE = Pattern.compile(
            "(?is).*(?:我(?:更)?喜欢|我偏好|以后请|请始终|i prefer|please always|from now on).{2,500}");

    private final MemoryItemRepository memories;
    private final MemorySafetyPolicy safety;
    private final ObjectMapper objectMapper;
    private final KnowledgeEmbeddingService embeddings;

    public MemoryCandidateService(
            MemoryItemRepository memories,
            MemorySafetyPolicy safety,
            ObjectMapper objectMapper,
            KnowledgeEmbeddingService embeddings) {
        this.memories = memories;
        this.safety = safety;
        this.objectMapper = objectMapper;
        this.embeddings = embeddings;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<MemoryView> capture(
            String agentId,
            String personaId,
            String conversationId,
            String runId,
            String userText,
            String memoryPolicyJson,
            ActorContext actor) {
        JsonNode policy = parse(memoryPolicyJson);
        JsonNode longTerm = policy.path("longTerm");
        if (!"PERSONALIZED".equals(policy.path("mode").asText()) || !longTerm.path("enabled").asBoolean(false)) {
            return Optional.empty();
        }
        Matcher explicit = EXPLICIT.matcher(userText == null ? "" : userText.trim());
        boolean explicitRequest = explicit.matches();
        String writeMode = longTerm.path("writeMode").asText("SUGGEST");
        boolean stablePreference = PREFERENCE.matcher(userText == null ? "" : userText.trim()).matches();
        if (!explicitRequest && ("EXPLICIT_ONLY".equals(writeMode) || !stablePreference)) {
            return Optional.empty();
        }

        String content = explicitRequest
                ? firstNonBlank(explicit.group(1), explicit.group(2))
                : userText.trim();
        content = truncate(content.trim(), 500);
        MemoryType type = stablePreference ? MemoryType.PROCEDURAL : MemoryType.SEMANTIC;
        if (!categoryAllowed(longTerm.path("categories"), type)) {
            return Optional.empty();
        }
        try {
            safety.validateUserMemory(content);
        } catch (IllegalArgumentException prohibited) {
            return Optional.empty();
        }
        if (memories.existsByTenantIdAndUserIdAndAgentIdAndPersonaIdAndContentIgnoreCaseAndStatusNot(
                actor.tenantId(), actor.userId(), agentId, personaId, content, MemoryStatus.REJECTED.name())) {
            return Optional.empty();
        }

        Instant now = Instant.now();
        Instant expiresAt = longTerm.path("ttlDays").isInt()
                ? now.plus(longTerm.path("ttlDays").asLong(), ChronoUnit.DAYS)
                : null;
        MemoryItemEntity candidate = new MemoryItemEntity(
                UUID.randomUUID().toString(),
                actor.tenantId(),
                actor.userId(),
                agentId,
                personaId,
                type,
                MemoryStatus.CANDIDATE,
                MemorySensitivity.NORMAL,
                content,
                explicitRequest ? 0.95 : 0.8,
                explicitRequest ? 0.8 : 0.6,
                conversationId,
                runId,
                explicitRequest
                        ? "Captured from an explicit user request to remember this information."
                        : "Captured from a stable preference stated by the user.",
                embeddings.embedForStorage(content).orElse(null),
                now,
                expiresAt);
        return Optional.of(MemoryView.from(memories.save(candidate)));
    }

    public Optional<MemoryView> capture(
            String agentId,
            String conversationId,
            String runId,
            String userText,
            String memoryPolicyJson,
            ActorContext actor) {
        return capture(agentId, null, conversationId, runId, userText, memoryPolicyJson, actor);
    }

    private static boolean categoryAllowed(JsonNode categories, MemoryType type) {
        if (!categories.isArray() || categories.isEmpty()) {
            return true;
        }
        for (JsonNode category : categories) {
            if (type.name().equals(category.asText())) {
                return true;
            }
        }
        return false;
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private JsonNode parse(String value) {
        try {
            return objectMapper.readTree(value == null || value.isBlank() ? "{}" : value);
        } catch (Exception ex) {
            throw new IllegalStateException("Memory policy snapshot is unreadable.", ex);
        }
    }
}
