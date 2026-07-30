package io.github.yourname.agentstudio.knowledge;

import io.github.yourname.agentstudio.security.ActorContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Knowledge write facade.
 *
 * <p>This first version uses a simple text chunk index in H2. The important
 * architectural rule is already present: tenant filtering and idempotency are
 * enforced before evidence can ever reach the model prompt.
 */
@Service
public class KnowledgeCommandService {

    private static final int CHUNK_SIZE = 1_200;

    private final KnowledgeBaseRepository bases;
    private final KnowledgeChunkRepository chunks;

    public KnowledgeCommandService(KnowledgeBaseRepository bases, KnowledgeChunkRepository chunks) {
        this.bases = bases;
        this.chunks = chunks;
    }

    @Transactional
    public KnowledgeBaseView create(CreateKnowledgeBaseCommand command, ActorContext actor) {
        var entity = bases.save(new KnowledgeBaseEntity(
                UUID.randomUUID().toString(),
                actor.tenantId(),
                command.name().trim(),
                command.description(),
                Instant.now()));
        return KnowledgeBaseView.from(entity);
    }

    @Transactional
    public IngestionResult ingest(String knowledgeBaseId, IngestDocumentCommand command, ActorContext actor) {
        bases.findByIdAndTenantId(knowledgeBaseId, actor.tenantId())
                .orElseThrow(() -> new IllegalArgumentException("Knowledge base not found: " + knowledgeBaseId));
        String hash = sha256(actor.tenantId() + ":" + knowledgeBaseId + ":" + command.sourceName() + ":" + command.content());
        if (chunks.existsByTenantIdAndKnowledgeBaseIdAndContentHash(actor.tenantId(), knowledgeBaseId, hash)) {
            return new IngestionResult(knowledgeBaseId, command.sourceName(), 0, true);
        }

        List<String> split = splitIntoChunks(command.content());
        for (int i = 0; i < split.size(); i++) {
            chunks.save(new KnowledgeChunkEntity(
                    actor.tenantId(),
                    knowledgeBaseId,
                    command.sourceName(),
                    hash,
                    i,
                    split.get(i),
                    Instant.now()));
        }
        return new IngestionResult(knowledgeBaseId, command.sourceName(), split.size(), false);
    }

    private static List<String> splitIntoChunks(String content) {
        List<String> result = new ArrayList<>();
        String normalized = content.replace("\r\n", "\n").trim();
        for (int start = 0; start < normalized.length(); start += CHUNK_SIZE) {
            result.add(normalized.substring(start, Math.min(start + CHUNK_SIZE, normalized.length())));
        }
        return result;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the Java platform.", ex);
        }
    }

    public record IngestionResult(String knowledgeBaseId, String sourceName, int chunkCount, boolean duplicate) {
    }
}
