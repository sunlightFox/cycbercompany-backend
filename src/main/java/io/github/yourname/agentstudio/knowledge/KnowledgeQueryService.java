package io.github.yourname.agentstudio.knowledge;

import io.github.yourname.agentstudio.config.AppProperties;
import io.github.yourname.agentstudio.security.ActorContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 知识库查询服务：负责列表、详情、文档列表和检索。
 *
 * <p>当前仍是轻量本地 RAG：没有向量库，但检索逻辑已经从“单个 LIKE 词”升级为
 * 多关键词召回 + 简单打分。这样在没有 embedding 的情况下，中英文都比第一版更稳。
 */
@Service
public class KnowledgeQueryService {

    /** Standard RRF rank constant; prevents the first result from dominating fusion. */
    private static final int RRF_RANK_CONSTANT = 60;

    private final KnowledgeBaseRepository bases;
    private final KnowledgeDocumentRepository documents;
    private final KnowledgeChunkRepository chunks;
    private final KnowledgeEmbeddingService embeddings;
    private final AppProperties properties;

    public KnowledgeQueryService(
            KnowledgeBaseRepository bases,
            KnowledgeDocumentRepository documents,
            KnowledgeChunkRepository chunks,
            KnowledgeEmbeddingService embeddings,
            AppProperties properties) {
        this.bases = bases;
        this.documents = documents;
        this.chunks = chunks;
        this.embeddings = embeddings;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public KnowledgeSettingsView settings() {
        var rag = properties.rag();
        return new KnowledgeSettingsView(
                rag != null && rag.embeddingEnabled(),
                rag == null ? "" : rag.embeddingModelProfileId(),
                rag == null || rag.maxEmbeddingChars() <= 0 ? 6_000 : rag.maxEmbeddingChars(),
                embeddings.vectorWeight());
    }

    @Transactional(readOnly = true)
    public List<KnowledgeBaseView> list(ActorContext actor) {
        return bases.findByTenantIdOrderByCreatedAtDesc(actor.tenantId()).stream()
                .map(base -> KnowledgeBaseView.from(
                        base,
                        documents.countByTenantIdAndKnowledgeBaseId(actor.tenantId(), base.id()),
                        chunks.countByTenantIdAndKnowledgeBaseId(actor.tenantId(), base.id())))
                .toList();
    }

    @Transactional(readOnly = true)
    public KnowledgeBaseDetailView get(String knowledgeBaseId, ActorContext actor) {
        KnowledgeBaseEntity base = requireBase(knowledgeBaseId, actor);
        List<KnowledgeDocumentView> docViews = listDocuments(knowledgeBaseId, actor);
        long chunkCount = chunks.countByTenantIdAndKnowledgeBaseId(actor.tenantId(), knowledgeBaseId);
        return new KnowledgeBaseDetailView(KnowledgeBaseView.from(base, docViews.size(), chunkCount), docViews);
    }

    @Transactional(readOnly = true)
    public List<KnowledgeDocumentView> listDocuments(String knowledgeBaseId, ActorContext actor) {
        requireBase(knowledgeBaseId, actor);
        return documents.findByTenantIdAndKnowledgeBaseIdOrderByCreatedAtDesc(actor.tenantId(), knowledgeBaseId)
                .stream()
                .map(KnowledgeDocumentView::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public KnowledgeStatsView stats(String knowledgeBaseId, ActorContext actor) {
        requireBase(knowledgeBaseId, actor);
        return new KnowledgeStatsView(
                knowledgeBaseId,
                documents.countByTenantIdAndKnowledgeBaseId(actor.tenantId(), knowledgeBaseId),
                chunks.countByTenantIdAndKnowledgeBaseId(actor.tenantId(), knowledgeBaseId));
    }

    @Transactional(readOnly = true)
    public List<KnowledgeChunkView> listDocumentChunks(String knowledgeBaseId, String documentId, ActorContext actor) {
        requireBase(knowledgeBaseId, actor);
        documents.findByIdAndTenantIdAndKnowledgeBaseId(documentId, actor.tenantId(), knowledgeBaseId)
                .orElseThrow(() -> new IllegalArgumentException("Knowledge document not found: " + documentId));
        return chunks.findByTenantIdAndKnowledgeBaseIdAndDocumentIdOrderByChunkIndexAsc(
                        actor.tenantId(), knowledgeBaseId, documentId)
                .stream()
                .map(KnowledgeChunkView::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public EvidenceBundle search(KnowledgeSearchCommand command, ActorContext actor) {
        int limit = command.limit() <= 0 ? 5 : Math.min(command.limit(), 20);
        List<String> ids = resolveKnowledgeBaseIds(command.knowledgeBaseIds(), actor);
        if (ids.isEmpty()) {
            return new EvidenceBundle(List.of());
        }

        // 权限校验：用户传了哪些知识库 id，就逐个确认它属于当前 tenant。

        List<String> terms = searchTerms(command.query());
        // Lexical and vector retrieval are intentionally independent. A semantic match must not
        // require a literal keyword hit, otherwise embeddings only become a tie-breaker.
        var queryVector = embeddings.embedForSearch(command.query());
        if (terms.isEmpty() && queryVector.isEmpty()) {
            return new EvidenceBundle(List.of());
        }

        List<ScoredChunk> candidates = chunks.findByTenantIdAndKnowledgeBaseIdIn(actor.tenantId(), ids).stream()
                .map(chunk -> score(chunk, terms, queryVector.orElse(null)))
                .filter(scored -> scored.lexicalScore() > 0 || scored.vectorScore() > 0)
                .toList();
        Map<KnowledgeChunkEntity, Integer> lexicalRanks = ranks(candidates, ScoredChunk::lexicalScore);
        Map<KnowledgeChunkEntity, Integer> vectorRanks = ranks(candidates, ScoredChunk::vectorScore);
        double vectorWeight = embeddings.vectorWeight();

        var evidence = candidates.stream()
                .map(candidate -> fuse(candidate, lexicalRanks, vectorRanks, vectorWeight))
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed()
                        .thenComparing(scored -> scored.chunk().documentId())
                        .thenComparingInt(scored -> scored.chunk().chunkIndex()))
                .limit(limit)
                .map(scored -> new EvidenceBundle.Evidence(
                        scored.chunk().id(),
                        scored.chunk().documentId(),
                        scored.chunk().knowledgeBaseId(),
                        scored.chunk().sourceName(),
                        scored.chunk().chunkIndex(),
                        bestQuote(scored.chunk().content(), terms),
                        scored.score()))
                .toList();
        return new EvidenceBundle(evidence);
    }

    /** Resolves the tenant-scoped knowledge domain captured by a RunSpec. */
    @Transactional(readOnly = true)
    public List<String> resolveKnowledgeBaseIds(List<String> requestedIds, ActorContext actor) {
        List<String> requested = requestedIds == null ? List.of() : requestedIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        List<String> ids = requested.isEmpty()
                ? bases.findByTenantIdOrderByCreatedAtDesc(actor.tenantId()).stream()
                        .map(KnowledgeBaseEntity::id)
                        .toList()
                : requested;
        ids.forEach(id -> requireBase(id, actor));
        return ids;
    }

    private KnowledgeBaseEntity requireBase(String knowledgeBaseId, ActorContext actor) {
        return bases.findByIdAndTenantId(knowledgeBaseId, actor.tenantId())
                .orElseThrow(() -> new IllegalArgumentException("Knowledge base not found: " + knowledgeBaseId));
    }

    /**
     * 轻量分词：
     * - 英文/数字按单词切；
     * - 中文没有 tokenizer 时，保留连续中文片段，并额外生成 2~4 字 ngram；
     * - 用 LinkedHashSet 去重并保持顺序，方便调试。
     */
    private static List<String> searchTerms(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        Set<String> terms = new LinkedHashSet<>();
        String normalized = query.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}\\p{IsHan}\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        for (String part : normalized.split("\\s+")) {
            if (part.length() >= 2) {
                terms.add(part);
            }
            if (containsHan(part)) {
                addChineseNgrams(part, terms);
            }
        }
        return new ArrayList<>(terms);
    }

    private static void addChineseNgrams(String value, Set<String> terms) {
        int[] sizes = {4, 3, 2};
        for (int size : sizes) {
            for (int i = 0; i + size <= value.length(); i++) {
                terms.add(value.substring(i, i + size));
            }
        }
    }

    private static boolean containsHan(String value) {
        return value.codePoints().anyMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }

    private static ScoredChunk score(KnowledgeChunkEntity chunk, List<String> terms, double[] queryVector) {
        String content = chunk.content() == null ? "" : chunk.content();
        String lower = content.toLowerCase(Locale.ROOT);
        double lexicalScore = 0;
        for (String term : terms) {
            int occurrences = countOccurrences(lower, term.toLowerCase(Locale.ROOT));
            if (occurrences > 0) {
                // 长词更有信息量，所以略微加权；重复出现也提高分数，但避免无限放大。
                lexicalScore += Math.min(3, occurrences) * (1.0 + Math.min(term.length(), 12) / 12.0);
            }
        }
        double vectorScore = 0;
        if (queryVector != null && chunk.embeddingVector() != null && !chunk.embeddingVector().isBlank()) {
            try {
                double cosine = KnowledgeEmbeddingService.cosineSimilarity(
                        queryVector,
                        KnowledgeEmbeddingService.deserialize(chunk.embeddingVector()));
                if (cosine > 0) {
                    vectorScore = cosine;
                }
            } catch (NumberFormatException ignored) {
                // 历史数据或手工修改可能产生脏向量；单个 chunk 解析失败不应该影响整次检索。
            }
        }
        return new ScoredChunk(chunk, lexicalScore, vectorScore, 0);
    }

    private static Map<KnowledgeChunkEntity, Integer> ranks(
            List<ScoredChunk> candidates,
            java.util.function.ToDoubleFunction<ScoredChunk> scoreExtractor) {
        Map<KnowledgeChunkEntity, Integer> ranks = new IdentityHashMap<>();
        List<ScoredChunk> sorted = candidates.stream()
                .filter(candidate -> scoreExtractor.applyAsDouble(candidate) > 0)
                .sorted(Comparator.comparingDouble(scoreExtractor).reversed()
                        .thenComparing(candidate -> candidate.chunk().documentId())
                        .thenComparingInt(candidate -> candidate.chunk().chunkIndex()))
                .toList();
        for (int index = 0; index < sorted.size(); index++) {
            ranks.put(sorted.get(index).chunk(), index + 1);
        }
        return ranks;
    }

    private static ScoredChunk fuse(
            ScoredChunk candidate,
            Map<KnowledgeChunkEntity, Integer> lexicalRanks,
            Map<KnowledgeChunkEntity, Integer> vectorRanks,
            double vectorWeight) {
        double score = reciprocalRank(lexicalRanks.get(candidate.chunk()))
                + Math.max(0, vectorWeight) * reciprocalRank(vectorRanks.get(candidate.chunk()));
        return new ScoredChunk(candidate.chunk(), candidate.lexicalScore(), candidate.vectorScore(), score);
    }

    private static double reciprocalRank(Integer rank) {
        return rank == null ? 0 : 1.0 / (RRF_RANK_CONSTANT + rank);
    }

    private static int countOccurrences(String content, String term) {
        if (term.isBlank()) {
            return 0;
        }
        int count = 0;
        int index = content.indexOf(term);
        while (index >= 0) {
            count++;
            index = content.indexOf(term, index + term.length());
        }
        return count;
    }

    private static String bestQuote(String content, List<String> terms) {
        if (content.length() <= 900) {
            return content;
        }
        int bestIndex = Integer.MAX_VALUE;
        String lower = content.toLowerCase(Locale.ROOT);
        for (String term : terms) {
            int index = lower.indexOf(term.toLowerCase(Locale.ROOT));
            if (index >= 0) {
                bestIndex = Math.min(bestIndex, index);
            }
        }
        if (bestIndex == Integer.MAX_VALUE) {
            return content.substring(0, 900) + "...";
        }
        int start = Math.max(0, bestIndex - 180);
        int end = Math.min(content.length(), start + 900);
        return (start > 0 ? "..." : "") + content.substring(start, end) + (end < content.length() ? "..." : "");
    }

    private record ScoredChunk(
            KnowledgeChunkEntity chunk,
            double lexicalScore,
            double vectorScore,
            double score) {
    }
}
