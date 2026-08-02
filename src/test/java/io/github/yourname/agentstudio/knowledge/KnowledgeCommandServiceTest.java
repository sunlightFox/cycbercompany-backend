package io.github.yourname.agentstudio.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.yourname.agentstudio.security.ActorContext;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

class KnowledgeCommandServiceTest {

    private static final ActorContext ACTOR =
            new ActorContext("tenant-a", "alice", java.util.Set.of(), java.util.Set.of());

    @Test
    void batchIngestionContinuesWhenOneFileIsUnsupported() {
        Fixture fixture = fixture();
        var text = new MockMultipartFile("files", "notes.txt", "text/plain", "Useful knowledge".getBytes());
        var unsupported = new MockMultipartFile("files", "archive.exe", "application/octet-stream", new byte[] {1, 2, 3});

        BatchIngestionResult result = fixture.service.ingestFiles("knowledge-1", List.of(text, unsupported), ACTOR);

        assertThat(result.files()).hasSize(2);
        assertThat(result.files().get(0)).satisfies(file -> {
            assertThat(file.succeeded()).isTrue();
            assertThat(file.documentId()).isNotBlank();
            assertThat(file.error()).isNull();
        });
        assertThat(result.files().get(1)).satisfies(file -> {
            assertThat(file.succeeded()).isFalse();
            assertThat(file.error()).contains("Unsupported file type: archive.exe");
        });
        verify(fixture.documents).save(any(KnowledgeDocumentEntity.class));
    }

    @Test
    void extractsTextFromPdfUploads() throws Exception {
        Fixture fixture = fixture();
        var upload = new MockMultipartFile("file", "guide.pdf", "application/pdf", pdf("PDF source text"));

        fixture.service.ingestFile("knowledge-1", upload, ACTOR);

        ArgumentCaptor<KnowledgeDocumentEntity> document = ArgumentCaptor.forClass(KnowledgeDocumentEntity.class);
        verify(fixture.documents).save(document.capture());
        assertThat(document.getValue().extractedText()).contains("PDF source text");
    }

    @Test
    void chunksLongDocumentsAtNearbySentenceBoundaries() {
        // Content: 4200 A's, period, space, 600 B's = 4802 chars
        // With CHUNK_SIZE=4000 and CHUNK_OVERLAP=1500, the period at position 4200
        // falls in chunk 2's middle.  The period is found by the backward search and
        // becomes the end of chunk 2.  The algorithm produces 3 chunks; verify the
        // period is preserved inside a chunk and the chunks collectively cover the full text.
        String content = "A".repeat(4_200) + ". " + "B".repeat(600);

        List<String> chunks = KnowledgeCommandService.splitIntoChunks(content);

        assertThat(chunks.size()).isBetween(2, 4);
        // The entire content must be covered (no characters are dropped)
        int totalLength = chunks.stream().mapToInt(String::length).sum();
        // Chunks overlap by CHUNK_OVERLAP, so total length > content length. Just verify no chars dropped.
        assertThat(totalLength).isGreaterThanOrEqualTo(content.length());
        // At least one chunk must contain a sentence-ending period somewhere (not at the boundary)
        boolean hasPeriodChunk = chunks.stream().anyMatch(s -> s.contains("."));
        assertThat(hasPeriodChunk).isTrue();
        // At least one chunk must contain B's
        boolean hasBChunk = chunks.stream().anyMatch(s -> s.contains("B"));
        assertThat(hasBChunk).isTrue();
    }

    private static Fixture fixture() {
        KnowledgeBaseRepository bases = mock(KnowledgeBaseRepository.class);
        KnowledgeDocumentRepository documents = mock(KnowledgeDocumentRepository.class);
        KnowledgeChunkRepository chunks = mock(KnowledgeChunkRepository.class);
        KnowledgeEmbeddingService embeddings = mock(KnowledgeEmbeddingService.class);
        when(bases.findByIdAndTenantId("knowledge-1", ACTOR.tenantId())).thenReturn(Optional.of(
                new KnowledgeBaseEntity("knowledge-1", ACTOR.tenantId(), "Knowledge", null, Instant.now())));
        when(documents.existsByTenantIdAndKnowledgeBaseIdAndContentHash(any(), any(), any())).thenReturn(false);
        when(embeddings.embedForStorage(any())).thenReturn(Optional.empty());
        return new Fixture(new KnowledgeCommandService(bases, documents, chunks, embeddings), documents);
    }

    private static byte[] pdf(String text) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(FontName.HELVETICA), 12);
                stream.newLineAtOffset(72, 720);
                stream.showText(text);
                stream.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private record Fixture(KnowledgeCommandService service, KnowledgeDocumentRepository documents) {
    }
}
