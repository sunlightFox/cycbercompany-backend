package io.github.yourname.agentstudio.document;

import java.io.IOException;
import java.util.Locale;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/** Extracts selectable text from PDF documents for chat attachments and knowledge ingestion. */
public final class PdfDocumentTextExtractor {

    private PdfDocumentTextExtractor() {
    }

    public static boolean supports(String sourceName, String contentType) {
        String name = sourceName == null ? "" : sourceName.toLowerCase(Locale.ROOT);
        String mediaType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        return name.endsWith(".pdf") || mediaType.equals("application/pdf");
    }

    public static String extract(byte[] bytes) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            return new PDFTextStripper().getText(document).trim();
        } catch (IOException | RuntimeException ex) {
            throw new IllegalArgumentException("Failed to extract text from PDF: " + ex.getMessage(), ex);
        }
    }
}
