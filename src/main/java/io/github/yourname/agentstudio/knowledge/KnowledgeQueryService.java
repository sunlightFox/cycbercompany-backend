package io.github.yourname.agentstudio.knowledge;

import io.github.yourname.agentstudio.security.ActorContext;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeQueryService {

    private final KnowledgeBaseRepository bases;
    private final KnowledgeChunkRepository chunks;

    public KnowledgeQueryService(KnowledgeBaseRepository bases, KnowledgeChunkRepository chunks) {
        this.bases = bases;
        this.chunks = chunks;
    }

    @Transactional(readOnly = true)
    public List<KnowledgeBaseView> list(ActorContext actor) {
        return bases.findByTenantIdOrderByCreatedAtDesc(actor.tenantId()).stream().map(KnowledgeBaseView::from).toList();
    }

    @Transactional(readOnly = true)
    public EvidenceBundle search(KnowledgeSearchCommand command, ActorContext actor) {
        int limit = command.limit() <= 0 ? 5 : Math.min(command.limit(), 20);
        List<String> ids = command.knowledgeBaseIds() == null ? List.of() : command.knowledgeBaseIds();
        if (ids.isEmpty()) {
            return new EvidenceBundle(List.of());
        }
        String term = strongestSearchTerm(command.query());
        var evidence = chunks.search(actor.tenantId(), ids, term).stream()
                .limit(limit)
                .map(chunk -> new EvidenceBundle.Evidence(
                        chunk.id(),
                        chunk.knowledgeBaseId(),
                        chunk.sourceName(),
                        chunk.chunkIndex(),
                        abbreviate(chunk.content()),
                        1.0))
                .toList();
        return new EvidenceBundle(evidence);
    }

    private static String strongestSearchTerm(String query) {
        String[] parts = query == null ? new String[0] : query.replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}\\s]", " ").split("\\s+");
        String best = "";
        for (String part : parts) {
            if (part.length() > best.length()) {
                best = part;
            }
        }
        return best.isBlank() ? query : best;
    }

    private static String abbreviate(String value) {
        return value.length() <= 700 ? value : value.substring(0, 700) + "...";
    }
}
