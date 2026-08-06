package io.github.yourname.agentstudio.knowledge;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Column;
import java.time.Instant;

/**
 * 文档切分后的最小检索单元。
 *
 * <p>检索通常返回 chunk，而不是整篇文档。chunk 保留 documentId、sourceName 和 chunkIndex，
 * 这样模型回答时可以把证据定位回具体文档。
 */
@Entity(name = "knowledge_chunk")
public class KnowledgeChunkEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    /** 数据库自增主键。 */
    private Long id;
    /** 租户 ID，防止跨租户检索。 */
    private String tenantId;
    /** 所属知识库。 */
    private String knowledgeBaseId;
    /** 所属文档。 */
    private String documentId;
    /** 原始来源名称，例如文件名。 */
    private String sourceName;
    /** 文档内容摘要，用于判断来源版本。 */
    private String contentHash;
    /** chunk 在文档中的顺序，从 0 开始。 */
    private int chunkIndex;
    @Column(length = 10_000)
    /** 实际参与关键词检索和模型上下文拼接的文本。 */
    private String content;
    /**
     * chunk 的 embedding 向量，使用逗号分隔的 double 字符串做第一版持久化。
     *
     * <p>这里不急着引入专用向量库，是为了先把 RAG 流程跑通；后续切到 pgvector/Milvus 时，
     * 可以把这个字段迁移成真正的 vector 列或外部向量索引。
     */
    @Column(length = 200_000)
    /** 可选的序列化向量；没有 Embedding 配置时可以为空。 */
    private String embeddingVector;
    /** chunk 创建时间。 */
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
