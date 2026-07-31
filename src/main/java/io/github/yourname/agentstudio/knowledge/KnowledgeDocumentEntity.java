package io.github.yourname.agentstudio.knowledge;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import java.time.Instant;

/**
 * 知识库里的“文档元数据”。
 *
 * <p>以前第一版只保存 chunk，没有文档表；这样能检索，但很难做“文档列表、删除文档、
 * 重建索引、统计文档大小”。这里新增文档表，把一个源文件/一段文本作为一条文档记录，
 * chunk 再通过 documentId 关联回来。
 */
@Entity(name = "knowledge_document")
public class KnowledgeDocumentEntity {

    @Id
    private String id;
    private String tenantId;
    private String knowledgeBaseId;
    private String sourceName;
    private String contentHash;
    private String contentType;
    private long contentLength;
    private int chunkCount;
    private Instant createdAt;
    private Instant updatedAt;
    @Column(length = 2_000)
    private String summary;
    @Lob
    @Column(length = 1_000_000)
    private String extractedText;

    protected KnowledgeDocumentEntity() {
    }

    public KnowledgeDocumentEntity(
            String id,
            String tenantId,
            String knowledgeBaseId,
            String sourceName,
            String contentHash,
            String contentType,
            long contentLength,
            int chunkCount,
            Instant createdAt,
            String summary,
            String extractedText) {
        this.id = id;
        this.tenantId = tenantId;
        this.knowledgeBaseId = knowledgeBaseId;
        this.sourceName = sourceName;
        this.contentHash = contentHash;
        this.contentType = contentType;
        this.contentLength = contentLength;
        this.chunkCount = chunkCount;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
        this.summary = summary;
        this.extractedText = extractedText;
    }

    public String id() { return id; }
    public String tenantId() { return tenantId; }
    public String knowledgeBaseId() { return knowledgeBaseId; }
    public String sourceName() { return sourceName; }
    public String contentHash() { return contentHash; }
    public String contentType() { return contentType; }
    public long contentLength() { return contentLength; }
    public int chunkCount() { return chunkCount; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public String summary() { return summary; }
    public String extractedText() { return extractedText; }

    /**
     * 重建索引后刷新文档的分块数量和摘要。
     *
     * <p>注意：这里不改 contentHash。hash 表示“原始抽取文本”的身份；重建索引只是重新
     * 生成 chunk，不代表文档内容变了。
     */
    public void markRebuilt(int chunkCount, String summary) {
        this.chunkCount = chunkCount;
        this.summary = summary;
        this.updatedAt = Instant.now();
    }
}
