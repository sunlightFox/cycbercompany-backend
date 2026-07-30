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
    private String sourceName;
    private String contentHash;
    private int chunkIndex;
    @Column(length = 10_000)
    private String content;
    private Instant createdAt;

    protected KnowledgeChunkEntity() {
    }

    public KnowledgeChunkEntity(
            String tenantId,
            String knowledgeBaseId,
            String sourceName,
            String contentHash,
            int chunkIndex,
            String content,
            Instant createdAt) {
        this.tenantId = tenantId;
        this.knowledgeBaseId = knowledgeBaseId;
        this.sourceName = sourceName;
        this.contentHash = contentHash;
        this.chunkIndex = chunkIndex;
        this.content = content;
        this.createdAt = createdAt;
    }

    public Long id() { return id; }
    public String tenantId() { return tenantId; }
    public String knowledgeBaseId() { return knowledgeBaseId; }
    public String sourceName() { return sourceName; }
    public String contentHash() { return contentHash; }
    public int chunkIndex() { return chunkIndex; }
    public String content() { return content; }
}
