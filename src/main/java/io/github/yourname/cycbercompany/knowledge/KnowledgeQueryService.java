package io.github.yourname.cycbercompany.knowledge;

import io.github.yourname.cycbercompany.security.ActorContext;
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
    private final KnowledgeDocumentRepository documentRepo;
    private final KnowledgeChunkRepository chunks;
    private final KnowledgeEmbeddingService embeddings;
    private final KnowledgeSettingsService settings;

    public KnowledgeQueryService(
            KnowledgeBaseRepository bases,
            KnowledgeDocumentRepository documents,
            KnowledgeChunkRepository chunks,
            KnowledgeEmbeddingService embeddings,
            KnowledgeSettingsService settings,
            KnowledgeDocumentRepository documentRepo) {
        this.bases = bases;
        this.documents = documents;
        this.chunks = chunks;
        this.embeddings = embeddings;
        this.settings = settings;
        this.documentRepo = documentRepo;
    }

    @Transactional(readOnly = true)
    public KnowledgeSettingsView settings() {
        return settings.view();
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
        // 先限制返回数量，再解析租户可见的知识库 ID，避免查询层被客户端输入无限放大。
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
        // 关键词分数和向量分数分别排名，再使用 RRF 融合，避免某一类分数的量纲支配结果。
        Map<KnowledgeChunkEntity, Integer> lexicalRanks = ranks(candidates, ScoredChunk::lexicalScore);
        Map<KnowledgeChunkEntity, Integer> vectorRanks = ranks(candidates, ScoredChunk::vectorScore);
        double vectorWeight = embeddings.vectorWeight();

        List<ScoredChunk> ranked = candidates.stream()
                .map(candidate -> fuse(candidate, lexicalRanks, vectorRanks, vectorWeight))
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed()
                        .thenComparing(scored -> scored.chunk().documentId())
                        .thenComparingInt(scored -> scored.chunk().chunkIndex()))
                .collect(java.util.stream.Collectors.toList());

        // Semantic post-filter: if the query asks for a minimum years-of-experience
        // requirement (e.g. "10 年以上"), exclude documents whose actual experience falls short.
        ExperienceRequirement req = parseExperienceRequirement(command.query());
        if (req != null) {
            Map<String, Integer> cache = new IdentityHashMap<>();
            ranked = ranked.stream().filter(scored -> {
                int years = cache.computeIfAbsent(scored.chunk().documentId(), id ->
                        documentRepo.findById(id).map(doc -> extractWorkYears(doc.extractedText())).orElse(-1));
                return years >= req.minYears;
            }).collect(java.util.stream.Collectors.toList());
            // If filtering removed everything, fall back to all results.
            if (ranked.isEmpty()) {
                ranked = candidates.stream()
                        .map(candidate -> fuse(candidate, lexicalRanks, vectorRanks, vectorWeight))
                        .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed().reversed()
                                .thenComparing(scored -> scored.chunk().documentId())
                                .thenComparingInt(scored -> scored.chunk().chunkIndex()))
                        .collect(java.util.stream.Collectors.toList());
            }
        }

        var evidence = ranked.stream().limit(limit)
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
        if (content.length() <= 3500) {
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
            return content.substring(0, 3500) + "\n\n[内容已截断，如需完整文档请提供原文件]";
        }
        int start = Math.max(0, bestIndex - 200);
        int end = Math.min(content.length(), start + 3500);
        return (start > 0 ? "...[前部已截断] " : "") + content.substring(start, end)
                + (end < content.length() ? " [后续内容已截断]" : "");
    }

    private record ScoredChunk(
            KnowledgeChunkEntity chunk,
            double lexicalScore,
            double vectorScore,
            double score) {
    }

    /**
     * Semantic post-filter: removes documents whose actual years of work experience
     * do not meet the minimum requirement expressed in the query (e.g. "10年以上").
     *
     * <p>Does NOT require a vector database; runs purely on the extracted document text
     * using regex patterns common in Chinese resumes.
     */
    private List<ScoredChunk> applyExperienceFilter(List<ScoredChunk> candidates, String query) {
        if (candidates.isEmpty() || query == null || query.isBlank()) {
            return candidates;
        }
        ExperienceRequirement req = parseExperienceRequirement(query);
        if (req == null) {
            return candidates; // no experience filter detected
        }
        // Collect the set of document IDs whose text we have already parsed.
        Map<String, Integer> docYearsCache = new IdentityHashMap<>();
        List<ScoredChunk> filtered = new ArrayList<>();
        for (ScoredChunk candidate : candidates) {
            String docId = candidate.chunk().documentId();
            int years = docYearsCache.computeIfAbsent(docId, id -> {
                return documentRepo.findById(id).map(doc -> extractWorkYears(doc.extractedText())).orElse(-1);
            });
            if (years >= req.minYears) {
                filtered.add(candidate);
            }
        }
        // If filtering removed everything (no document meets the bar), fall back to all results.
        return filtered.isEmpty() ? candidates : filtered;
    }

    /** Detects whether the query expresses a minimum years-of-experience requirement. */
    private ExperienceRequirement parseExperienceRequirement(String query) {
        String q = query.trim();
        // "X年以上" / "X年以下" / "X 年以上"
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "([0-9零一二三四五六七八九十百]+)\s*年\s*(以上|以下|经验|工作经验)?",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher m = p.matcher(q);
        int bestMin = -1;
        while (m.find()) {
            String numStr = m.group(1);
            int num = chineseToNumber(numStr);
            String qualifier = m.group(2) != null ? m.group(2).toLowerCase() : "";
            if (qualifier.contains("以下") || qualifier.contains("经验") || qualifier.isEmpty()) {
                bestMin = Math.max(bestMin, num);
            }
        }
        // Also detect bare "10 年" without qualifier (e.g. "10 年 Java 工程师" is ambiguous;
        // only apply when the context suggests a requirement: contains "要求" or "年以上" etc.)
        if (bestMin < 0 && (q.contains("年以上") || q.contains("10 年") && q.contains("要求"))) {
            m = java.util.regex.Pattern.compile("([0-9零一二三四五六七八九十百]+)\s*年").matcher(q);
            while (m.find()) {
                int num = chineseToNumber(m.group(1));
                bestMin = Math.max(bestMin, num);
            }
        }
        if (bestMin < 0) {
            return null;
        }
        return new ExperienceRequirement(bestMin);
    }

    /** Converts Chinese numerals and mixed strings to an integer. */
    private static int chineseToNumber(String s) {
        if (s == null || s.isBlank()) return -1;
        // pure Arabic digits
        if (s.matches("[0-9]+")) {
            return Integer.parseInt(s);
        }
        // mixed: "10年" already stripped, try "七八年" style
        s = s.replace("零", "0").replace("一", "1").replace("二", "2").replace("三", "3")
             .replace("四", "4").replace("五", "5").replace("六", "6")
             .replace("七", "7").replace("八", "8").replace("九", "9");
        s = s.replaceAll("[^0-9]", "");
        if (s.isEmpty()) return -1;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return -1; }
    }

    /**
     * Extracts the candidate'92s total years of work experience from the raw resume text.
     * <p>Checks multiple patterns in priority order:
     * <ol>
     *   <li>Explicit "工作年限：X年" label</li>
     *   <li>Year range: earliest start to "至今" or a concrete end year</li>
     *   <li>Falls back to -1 (unknown) if nothing matches</li>
     * </ol>
     */
    private int extractWorkYears(String text) {
        if (text == null || text.isBlank()) return -1;
        String t = text.trim();
        // Pattern 1: "工作年限：X年" or "工作年限 X 年"
        java.util.regex.Pattern p1 = java.util.regex.Pattern.compile(
                "工作年限[：:\s]*([0-9零一二三四五六七八九十百]+)\s*年", java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher m1 = p1.matcher(t);
        while (m1.find()) {
            int v = chineseToNumber(m1.group(1));
            if (v > 0) return v;
        }
        // Pattern 2: explicit range label "工作年限：X-Y年"
        java.util.regex.Pattern p2 = java.util.regex.Pattern.compile(
                "工作年限[：:\s]*([0-9]+)\s*[-~至到]\s*([0-9零一二三四五六七八九十百]+)\s*年",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher m2 = p2.matcher(t);
        if (m2.find()) {
            int start = Integer.parseInt(m2.group(1));
            int end = chineseToNumber(m2.group(2));
            return end - start;
        }
        // Pattern 3: earliest work start year to current
        java.util.regex.Pattern p3 = java.util.regex.Pattern.compile(
                "([12][0-9]{3})\\.[0-9]{1,2}\s*[-~至到]\s*至今");
        java.util.regex.Matcher m3 = p3.matcher(t);
        int earliest = Integer.MAX_VALUE;
        while (m3.find()) {
            earliest = Math.min(earliest, Integer.parseInt(m3.group(1)));
        }
        if (earliest != Integer.MAX_VALUE) {
            return java.time.Year.now().getValue() - earliest;
        }
        // Pattern 4: two explicit year-month dates (take the span)
        java.util.regex.Pattern p4 = java.util.regex.Pattern.compile(
                "([12][0-9]{3})\\.[0-9]{1,2}");
        java.util.regex.Matcher m4 = p4.matcher(t);
        int firstYear = Integer.MAX_VALUE, lastYear = 0;
        java.util.regex.Matcher m4b = p4.matcher(t);
        while (m4b.find()) {
            int y = Integer.parseInt(m4b.group(1));
            firstYear = Math.min(firstYear, y);
            lastYear = Math.max(lastYear, y);
        }
        if (firstYear != Integer.MAX_VALUE) {
            return lastYear - firstYear;
        }
        return -1;
    }

    private record ExperienceRequirement(int minYears) {}

}
