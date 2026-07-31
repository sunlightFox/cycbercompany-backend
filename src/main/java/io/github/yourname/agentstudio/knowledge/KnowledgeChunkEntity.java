package io.github.yourname.agentstudio.knowledge;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Column;
import java.time.Instant;

@Entity(name = "knowledge_chunk")
public class KnowledgeChunkEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String tenantId;
    private String knowledgeBaseId;
    private String documentId;
    private String sourceName;
    private String contentHash;
    private int chunkIndex;
    @Column(length = 10_000)
    private String content;
    /**
     * chunk 的 embedding 向量，使用逗号分隔的 double 字符串做第一版持久化。
     *
     * <p>这里不急着引入专用向量库，是为了先把 RAG 流程跑通；后续切到 pgvector/Milvus 时，
     * 可以把这个字段迁移成真正的 vector 列或外部向量索引。
     */
    @Column(length = 200_000)
    private String embeddingVector;
    private Instant createdAt;

    protected KnowledgeChunkEntity() {
    }

    public KnowledgeChunkEntity(
            String tenantId,
            String knowledgeBaseId,
            String documentId,
            String sourceName,
            String contentHash,
            int chunkIndex,
            String content,
            String embeddingVector,
            Instant createdAt) {
        this.tenantId = tenantId;
        this.knowledgeBaseId = knowledgeBaseId;
        this.documentId = documentId;
        this.sourceName = sourceName;
        this.contentHash = contentHash;
        this.chunkIndex = chunkIndex;
        this.content = content;
        this.embeddingVector = embeddingVector;
        this.createdAt = createdAt;
    }

    public Long id() { return id; }
    public String tenantId() { return tenantId; }
    public String knowledgeBaseId() { return knowledgeBaseId; }
    public String documentId() { return documentId; }
    public String sourceName() { return sourceName; }
    public String contentHash() { return contentHash; }
    public int chunkIndex() { return chunkIndex; }
    public String content() { return content; }
    public String embeddingVector() { return embeddingVector; }
}
