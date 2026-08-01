package io.github.yourname.agentstudio.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.yourname.agentstudio.security.ActorContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class KnowledgeQueryServiceTest {

    private static final ActorContext ACTOR =
            new ActorContext("tenant-a", "alice", java.util.Set.of(), java.util.Set.of());

    @Test
    void searchesAllTenantKnowledgeBasesWhenNoScopeIsProvided() {
        KnowledgeBaseRepository bases = mock(KnowledgeBaseRepository.class);
        KnowledgeDocumentRepository documents = mock(KnowledgeDocumentRepository.class);
        KnowledgeChunkRepository chunks = mock(KnowledgeChunkRepository.class);
        KnowledgeEmbeddingService embeddings = mock(KnowledgeEmbeddingService.class);
        KnowledgeBaseEntity resume = new KnowledgeBaseEntity(
                "resume", ACTOR.tenantId(), "Resume", null, Instant.now());
        KnowledgeChunkEntity profile = new KnowledgeChunkEntity(
                ACTOR.tenantId(),
                resume.id(),
                "document-1",
                "candidate.pdf",
                "hash",
                0,
                "武一凡出生于1993年1月20日。",
                null,
                Instant.now());
        when(bases.findByTenantIdOrderByCreatedAtDesc(ACTOR.tenantId())).thenReturn(List.of(resume));
        when(bases.findByIdAndTenantId(resume.id(), ACTOR.tenantId())).thenReturn(Optional.of(resume));
        when(chunks.findByTenantIdAndKnowledgeBaseIdIn(eq(ACTOR.tenantId()), eq(List.of(resume.id()))))
                .thenReturn(List.of(profile));
        when(embeddings.embedForSearch(any())).thenReturn(java.util.Optional.empty());

        EvidenceBundle result = new KnowledgeQueryService(bases, documents, chunks, embeddings, null)
                .search(new KnowledgeSearchCommand(List.of(), "武一凡年龄", 5), ACTOR);

        assertThat(result.evidence()).singleElement().satisfies(evidence -> {
            assertThat(evidence.knowledgeBaseId()).isEqualTo(resume.id());
            assertThat(evidence.quote()).contains("武一凡出生于1993年1月20日");
        });
        verify(bases).findByTenantIdOrderByCreatedAtDesc(ACTOR.tenantId());
    }

    @Test
    void resolvesExplicitKnowledgeBasesAfterTrimmingAndDeduplicatingIds() {
        KnowledgeBaseRepository bases = mock(KnowledgeBaseRepository.class);
        KnowledgeBaseEntity resume = new KnowledgeBaseEntity(
                "resume", ACTOR.tenantId(), "Resume", null, Instant.now());
        when(bases.findByIdAndTenantId(resume.id(), ACTOR.tenantId())).thenReturn(Optional.of(resume));

        List<String> ids = new KnowledgeQueryService(
                bases,
                mock(KnowledgeDocumentRepository.class),
                mock(KnowledgeChunkRepository.class),
                mock(KnowledgeEmbeddingService.class),
                null).resolveKnowledgeBaseIds(List.of(" resume ", "resume"), ACTOR);

        assertThat(ids).containsExactly("resume");
        verify(bases).findByIdAndTenantId("resume", ACTOR.tenantId());
    }

    @Test
    void rejectsExplicitKnowledgeBaseOutsideCurrentTenant() {
        KnowledgeBaseRepository bases = mock(KnowledgeBaseRepository.class);
        when(bases.findByIdAndTenantId("other-tenant-base", ACTOR.tenantId())).thenReturn(Optional.empty());
        KnowledgeQueryService service = new KnowledgeQueryService(
                bases,
                mock(KnowledgeDocumentRepository.class),
                mock(KnowledgeChunkRepository.class),
                mock(KnowledgeEmbeddingService.class),
                null);

        assertThatThrownBy(() -> service.resolveKnowledgeBaseIds(List.of("other-tenant-base"), ACTOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Knowledge base not found");
    }

    @Test
    void semanticCandidateIsReturnedWithoutLiteralKeywordOverlap() {
        KnowledgeBaseRepository bases = mock(KnowledgeBaseRepository.class);
        KnowledgeChunkRepository chunks = mock(KnowledgeChunkRepository.class);
        KnowledgeEmbeddingService embeddings = mock(KnowledgeEmbeddingService.class);
        KnowledgeBaseEntity base = new KnowledgeBaseEntity("kb-1", ACTOR.tenantId(), "Knowledge", null, Instant.now());
        KnowledgeChunkEntity lexical = chunk(base.id(), "doc-keyword", 0, "needle needle needle", "0,1");
        KnowledgeChunkEntity semantic = chunk(base.id(), "doc-semantic", 1, "related concept expressed differently", "1,0");
        when(bases.findByIdAndTenantId(base.id(), ACTOR.tenantId())).thenReturn(Optional.of(base));
        when(chunks.findByTenantIdAndKnowledgeBaseIdIn(ACTOR.tenantId(), List.of(base.id())))
                .thenReturn(List.of(lexical, semantic));
        when(embeddings.embedForSearch("needle")).thenReturn(Optional.of(new double[] {1, 0}));
        when(embeddings.vectorWeight()).thenReturn(1.0);

        EvidenceBundle result = new KnowledgeQueryService(
                bases, mock(KnowledgeDocumentRepository.class), chunks, embeddings, null)
                .search(new KnowledgeSearchCommand(List.of(base.id()), "needle", 5), ACTOR);

        assertThat(result.evidence()).extracting(EvidenceBundle.Evidence::documentId)
                .contains("doc-keyword", "doc-semantic");
    }

    @Test
    void reciprocalRankFusionPromotesEvidenceSupportedByBothChannels() {
        KnowledgeBaseRepository bases = mock(KnowledgeBaseRepository.class);
        KnowledgeChunkRepository chunks = mock(KnowledgeChunkRepository.class);
        KnowledgeEmbeddingService embeddings = mock(KnowledgeEmbeddingService.class);
        KnowledgeBaseEntity base = new KnowledgeBaseEntity("kb-1", ACTOR.tenantId(), "Knowledge", null, Instant.now());
        KnowledgeChunkEntity lexicalOnly = chunk(base.id(), "doc-lexical", 0, "needle needle needle needle", "0,1");
        KnowledgeChunkEntity hybrid = chunk(base.id(), "doc-hybrid", 1, "needle", "1,0");
        when(bases.findByIdAndTenantId(base.id(), ACTOR.tenantId())).thenReturn(Optional.of(base));
        when(chunks.findByTenantIdAndKnowledgeBaseIdIn(ACTOR.tenantId(), List.of(base.id())))
                .thenReturn(List.of(lexicalOnly, hybrid));
        when(embeddings.embedForSearch("needle")).thenReturn(Optional.of(new double[] {1, 0}));
        when(embeddings.vectorWeight()).thenReturn(1.0);

        EvidenceBundle result = new KnowledgeQueryService(
                bases, mock(KnowledgeDocumentRepository.class), chunks, embeddings, null)
                .search(new KnowledgeSearchCommand(List.of(base.id()), "needle", 5), ACTOR);

        assertThat(result.evidence().getFirst().documentId()).isEqualTo("doc-hybrid");
    }

    private static KnowledgeChunkEntity chunk(
            String knowledgeBaseId,
            String documentId,
            int chunkIndex,
            String content,
            String embedding) {
        return new KnowledgeChunkEntity(
                ACTOR.tenantId(), knowledgeBaseId, documentId, documentId + ".txt", "hash",
                chunkIndex, content, embedding, Instant.now());
    }
}
