package io.github.yourname.cycbercompany.knowledge;

import io.github.yourname.cycbercompany.security.ActorContext;
import io.github.yourname.cycbercompany.document.OfficeDocumentTextExtractor;
import io.github.yourname.cycbercompany.document.PdfDocumentTextExtractor;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 知识库写入服务：负责创建知识库、摄取文档、删除文档/知识库。
 *
 * <p>重要原则：所有写操作都必须带 ActorContext，并且按 tenantId 过滤。这样模型提示词、
 * MCP 参数或前端传来的任意 id，都不能越权访问其他租户的数据。
 */
@Service
public class KnowledgeCommandService {

    /**
     * 单块最大字符数。这里先用字符长度实现，后续接 tokenizer 后可以改成 token 数。
     */
    private static final int DEFAULT_CHUNK_SIZE = 4_000;

    /**
     * 相邻 chunk 的重叠字符数。重叠能减少“答案刚好跨两个 chunk 边界”导致召回失败的问题。
     */
    private static final int DEFAULT_CHUNK_OVERLAP = 1_500;

    private final KnowledgeBaseRepository bases;
    private final KnowledgeDocumentRepository documents;
    private final KnowledgeChunkRepository chunks;
    private final KnowledgeEmbeddingService embeddings;
    private final KnowledgeSettingsService settings;

    @Autowired
    public KnowledgeCommandService(
            KnowledgeBaseRepository bases,
            KnowledgeDocumentRepository documents,
            KnowledgeChunkRepository chunks,
            KnowledgeEmbeddingService embeddings,
            KnowledgeSettingsService settings) {
        this.bases = bases;
        this.documents = documents;
        this.chunks = chunks;
        this.embeddings = embeddings;
        this.settings = settings;
    }

    /** Retained for focused unit tests that do not need runtime settings. */
    KnowledgeCommandService(
            KnowledgeBaseRepository bases,
            KnowledgeDocumentRepository documents,
            KnowledgeChunkRepository chunks,
            KnowledgeEmbeddingService embeddings) {
        this(bases, documents, chunks, embeddings, null);
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
    public KnowledgeBaseView update(String knowledgeBaseId, UpdateKnowledgeBaseCommand command, ActorContext actor) {
        KnowledgeBaseEntity base = requireBase(knowledgeBaseId, actor);
        base.update(command.name().trim(), command.description());
        return KnowledgeBaseView.from(
                bases.save(base),
                documents.countByTenantIdAndKnowledgeBaseId(actor.tenantId(), knowledgeBaseId),
                chunks.countByTenantIdAndKnowledgeBaseId(actor.tenantId(), knowledgeBaseId));
    }

    @Transactional
    public IngestionResult ingest(String knowledgeBaseId, IngestDocumentCommand command, ActorContext actor) {
        return ingestText(
                knowledgeBaseId,
                command.sourceName(),
                command.content(),
                "text/plain",
                actor);
    }

    @Transactional
    public IngestionResult ingestFile(String knowledgeBaseId, MultipartFile file, ActorContext actor) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty.");
        }
        try {
            String sourceName = sourceName(file);
            String contentType = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
            String content = extractText(sourceName, contentType, file.getBytes());
            return ingestText(knowledgeBaseId, sourceName, content, contentType, actor);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to ingest uploaded file: " + ex.getMessage(), ex);
        }
    }

    @Transactional
    public BatchIngestionResult ingestFiles(String knowledgeBaseId, List<MultipartFile> files, ActorContext actor) {
        requireBase(knowledgeBaseId, actor);
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("At least one file is required.");
        }
        if (files.size() > 20) {
            throw new IllegalArgumentException("A maximum of 20 files can be uploaded at once.");
        }

        List<BatchIngestionResult.FileIngestionResult> results = new ArrayList<>();
        for (MultipartFile file : files) {
            String sourceName = sourceName(file);
            try {
                IngestionResult result = ingestFile(knowledgeBaseId, file, actor);
                results.add(new BatchIngestionResult.FileIngestionResult(
                        result.sourceName(), result.documentId(), result.chunkCount(), result.duplicate(), true, null));
            } catch (Exception ex) {
                results.add(new BatchIngestionResult.FileIngestionResult(
                        sourceName, null, 0, false, false, userFacingError(ex)));
            }
        }
        return new BatchIngestionResult(results);
    }

    @Transactional
    public void deleteKnowledgeBase(String knowledgeBaseId, ActorContext actor) {
        KnowledgeBaseEntity base = requireBase(knowledgeBaseId, actor);
        chunks.deleteByTenantIdAndKnowledgeBaseId(actor.tenantId(), knowledgeBaseId);
        documents.deleteByTenantIdAndKnowledgeBaseId(actor.tenantId(), knowledgeBaseId);
        bases.delete(base);
    }

    @Transactional
    public void deleteDocument(String knowledgeBaseId, String documentId, ActorContext actor) {
        requireBase(knowledgeBaseId, actor);
        KnowledgeDocumentEntity document = documents.findByIdAndTenantIdAndKnowledgeBaseId(
                        documentId, actor.tenantId(), knowledgeBaseId)
                .orElseThrow(() -> new IllegalArgumentException("Knowledge document not found: " + documentId));
        chunks.deleteByTenantIdAndKnowledgeBaseIdAndDocumentId(actor.tenantId(), knowledgeBaseId, document.id());
        documents.delete(document);
    }

    @Transactional
    public RebuildIndexResult rebuildDocument(String knowledgeBaseId, String documentId, ActorContext actor) {
        requireBase(knowledgeBaseId, actor);
        KnowledgeDocumentEntity document = documents.findByIdAndTenantIdAndKnowledgeBaseId(
                        documentId, actor.tenantId(), knowledgeBaseId)
                .orElseThrow(() -> new IllegalArgumentException("Knowledge document not found: " + documentId));
        if (document.extractedText() == null || document.extractedText().isBlank()) {
            throw new IllegalArgumentException("Document cannot be rebuilt because extracted text was not stored: " + documentId);
        }
        int count = rebuildChunks(document, actor);
        return new RebuildIndexResult(knowledgeBaseId, documentId, 1, count);
    }

    @Transactional
    public RebuildIndexResult rebuildKnowledgeBase(String knowledgeBaseId, ActorContext actor) {
        requireBase(knowledgeBaseId, actor);
        int rebuiltDocuments = 0;
        int totalChunks = 0;
        for (KnowledgeDocumentEntity document : documents.findByTenantIdAndKnowledgeBaseIdOrderByCreatedAtDesc(
                actor.tenantId(), knowledgeBaseId)) {
            if (document.extractedText() == null || document.extractedText().isBlank()) {
                continue;
            }
            totalChunks += rebuildChunks(document, actor);
            rebuiltDocuments++;
        }
        return new RebuildIndexResult(knowledgeBaseId, null, rebuiltDocuments, totalChunks);
    }

    @Transactional
    public KnowledgeStatsView clearDocuments(String knowledgeBaseId, ActorContext actor) {
        requireBase(knowledgeBaseId, actor);
        chunks.deleteByTenantIdAndKnowledgeBaseId(actor.tenantId(), knowledgeBaseId);
        documents.deleteByTenantIdAndKnowledgeBaseId(actor.tenantId(), knowledgeBaseId);
        return new KnowledgeStatsView(knowledgeBaseId, 0, 0);
    }

    private IngestionResult ingestText(
            String knowledgeBaseId,
            String sourceName,
            String rawContent,
            String contentType,
            ActorContext actor) {
        requireBase(knowledgeBaseId, actor);
        String content = normalizeText(rawContent);
        if (content.isBlank()) {
            throw new IllegalArgumentException("Document content is blank after text extraction.");
        }

        String hash = sha256(actor.tenantId() + ":" + knowledgeBaseId + ":" + sourceName + ":" + content);
        if (documents.existsByTenantIdAndKnowledgeBaseIdAndContentHash(actor.tenantId(), knowledgeBaseId, hash)) {
            return new IngestionResult(knowledgeBaseId, null, sourceName, 0, true);
        }

        String documentId = UUID.randomUUID().toString();
        List<String> split = splitIntoChunks(content, configuredChunkSize(), configuredChunkOverlap());
        Instant now = Instant.now();
        documents.save(new KnowledgeDocumentEntity(
                documentId,
                actor.tenantId(),
                knowledgeBaseId,
                sourceName,
                hash,
                contentType,
                content.length(),
                split.size(),
                now,
                summarize(content),
                content));

        for (int i = 0; i < split.size(); i++) {
            String chunkContent = split.get(i);
            // 如果配置了 embedding 模型，这里会为每个 chunk 生成向量；没有配置时返回 null，保持关键词检索可用。
            String embeddingVector = embeddings.embedForStorage(chunkContent).orElse(null);
            chunks.save(new KnowledgeChunkEntity(
                    actor.tenantId(),
                    knowledgeBaseId,
                    documentId,
                    sourceName,
                    hash,
                    i,
                    chunkContent,
                    embeddingVector,
                    now));
        }
        return new IngestionResult(knowledgeBaseId, documentId, sourceName, split.size(), false);
    }

    private int rebuildChunks(KnowledgeDocumentEntity document, ActorContext actor) {
        chunks.deleteByTenantIdAndKnowledgeBaseIdAndDocumentId(actor.tenantId(), document.knowledgeBaseId(), document.id());
        List<String> split = splitIntoChunks(
                document.extractedText(), configuredChunkSize(), configuredChunkOverlap());
        Instant now = Instant.now();
        for (int i = 0; i < split.size(); i++) {
            String chunkContent = split.get(i);
            String embeddingVector = embeddings.embedForStorage(chunkContent).orElse(null);
            chunks.save(new KnowledgeChunkEntity(
                    actor.tenantId(),
                    document.knowledgeBaseId(),
                    document.id(),
                    document.sourceName(),
                    document.contentHash(),
                    i,
                    chunkContent,
                    embeddingVector,
                    now));
        }
        document.markRebuilt(split.size(), summarize(document.extractedText()));
        documents.save(document);
        return split.size();
    }

    private KnowledgeBaseEntity requireBase(String knowledgeBaseId, ActorContext actor) {
        return bases.findByIdAndTenantId(knowledgeBaseId, actor.tenantId())
                .orElseThrow(() -> new IllegalArgumentException("Knowledge base not found: " + knowledgeBaseId));
    }

    /**
     * 文件文本抽取的第一版：
     * - txt/md/json/csv/yml 等按 UTF-8 文本读取；
     * - html 用 jsoup 去掉标签，只保留正文；
     * - docx/xlsx/pptx 本质是 zip 包，里面是 XML；这里用 JDK ZipInputStream 做基础抽取；
     * - pdf 二进制结构复杂，先明确提示不支持，后续单独接 PDFBox。
     */
    private String extractText(String sourceName, String contentType, byte[] bytes) {
        String lowerName = sourceName.toLowerCase();
        if (lowerName.endsWith(".html") || lowerName.endsWith(".htm") || contentType.contains("html")) {
            return Jsoup.parse(new String(bytes, StandardCharsets.UTF_8)).text();
        }
        if (isLikelyPlainText(lowerName, contentType)) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        if (OfficeDocumentTextExtractor.supports(sourceName)) {
            return OfficeDocumentTextExtractor.extract(sourceName, bytes);
        }
        if (lowerName.endsWith(".docx")) {
            return OfficeDocumentTextExtractor.extract(sourceName, bytes);
        }
        if (lowerName.endsWith(".xlsx")) {
            return OfficeDocumentTextExtractor.extract(sourceName, bytes);
        }
        if (lowerName.endsWith(".pptx")) {
            return OfficeDocumentTextExtractor.extract(sourceName, bytes);
        }
        if (lowerName.endsWith(".pdf")) {
            return PdfDocumentTextExtractor.extract(bytes);
        }
        throw new IllegalArgumentException("Unsupported file type: " + sourceName);
    }

    private static boolean isLikelyPlainText(String lowerName, String contentType) {
        return contentType.startsWith("text/")
                || lowerName.endsWith(".txt")
                || lowerName.endsWith(".md")
                || lowerName.endsWith(".markdown")
                || lowerName.endsWith(".json")
                || lowerName.endsWith(".csv")
                || lowerName.endsWith(".tsv")
                || lowerName.endsWith(".yml")
                || lowerName.endsWith(".yaml")
                || lowerName.endsWith(".xml")
                || lowerName.endsWith(".log");
    }

    private static String sourceName(MultipartFile file) {
        if (file == null || file.getOriginalFilename() == null || file.getOriginalFilename().isBlank()) {
            return "uploaded-file";
        }
        return file.getOriginalFilename();
    }

    private static String userFacingError(Exception ex) {
        Throwable cause = ex;
        while (cause.getCause() != null && (cause instanceof IllegalStateException || cause.getMessage() == null)) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? "Failed to ingest file." : cause.getMessage();
    }

    static List<String> splitIntoChunks(String content) {
        return splitIntoChunks(content, DEFAULT_CHUNK_SIZE, DEFAULT_CHUNK_OVERLAP);
    }

    static List<String> splitIntoChunks(String content, int chunkSize, int chunkOverlap) {
        if (chunkSize <= 0 || chunkOverlap < 0 || chunkOverlap >= chunkSize) {
            throw new IllegalArgumentException("Chunk overlap must be smaller than chunk size.");
        }
        List<String> result = new ArrayList<>();
        String normalized = normalizeText(content);
        for (int start = 0; start < normalized.length();) {
            int end = preferredChunkEnd(normalized, start, chunkSize);
            result.add(normalized.substring(start, end).trim());
            if (end == normalized.length()) {
                break;
            }
            start = Math.max(start + 1, end - chunkOverlap);
        }
        return result;
    }

    private static int preferredChunkEnd(String content, int start, int chunkSize) {
        int target = Math.min(start + chunkSize, content.length());
        if (target == content.length()) {
            return target;
        }
        // Prefer a nearby paragraph, line, or sentence boundary. The lower bound keeps a
        // short trailing sentence from turning a 1,200-character chunk into a tiny fragment.
        int lowerBound = Math.min(target - 1, Math.max(start + chunkSize / 2, target - 2500));
        for (int index = target; index > lowerBound; index--) {
            char current = content.charAt(index - 1);
            if (current == '\n') {
                return index - 1;
            }
            if (current == '.' || current == '!' || current == '?' || current == '\u3002'
                    || current == '\uff01' || current == '\uff1f') {
                return index;
            }
        }
        return target;
    }

    private int configuredChunkSize() {
        return settings == null ? DEFAULT_CHUNK_SIZE : settings.chunkSize();
    }

    private int configuredChunkOverlap() {
        return settings == null ? DEFAULT_CHUNK_OVERLAP : settings.chunkOverlap();
    }

    private static String normalizeText(String content) {
        if (content == null) {
            return "";
        }
        return content
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\\t\\x0B\\f]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private static String summarize(String content) {
        String compact = content.replaceAll("\\s+", " ").trim();
        return compact.length() <= 240 ? compact : compact.substring(0, 240) + "...";
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the Java platform.", ex);
        }
    }

    public record IngestionResult(
            String knowledgeBaseId,
            String documentId,
            String sourceName,
            int chunkCount,
            boolean duplicate) {
    }
}
