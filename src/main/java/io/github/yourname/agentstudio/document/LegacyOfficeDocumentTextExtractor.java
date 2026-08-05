package io.github.yourname.agentstudio.document;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;
import org.apache.poi.hslf.usermodel.HSLFShape;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTextShape;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;

/** Extracts text from legacy binary Office formats supported by Apache POI scratchpad. */
public final class LegacyOfficeDocumentTextExtractor {

    private LegacyOfficeDocumentTextExtractor() {
    }

    public static boolean supports(String sourceName) {
        String name = sourceName == null ? "" : sourceName.toLowerCase(Locale.ROOT);
        return name.endsWith(".doc") || name.endsWith(".xls") || name.endsWith(".ppt");
    }

    public static String extract(String sourceName, byte[] bytes) {
        String name = sourceName == null ? "" : sourceName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".doc")) return extractDoc(bytes);
        if (name.endsWith(".xls")) return extractXls(bytes);
        if (name.endsWith(".ppt")) return extractPpt(bytes);
        throw new IllegalArgumentException("Unsupported legacy Office file type: " + sourceName);
    }

    private static String extractDoc(byte[] bytes) {
        try (var input = new ByteArrayInputStream(bytes); var document = new HWPFDocument(input)) {
            return document.getRange().text().replace('\u0007', ' ').replaceAll("\\s+", " ").trim();
        } catch (IOException | RuntimeException ex) {
            throw new IllegalArgumentException("Failed to read DOC content: " + ex.getMessage(), ex);
        }
    }

    private static String extractXls(byte[] bytes) {
        try (var input = new ByteArrayInputStream(bytes); var workbook = new HSSFWorkbook(input)) {
            StringBuilder text = new StringBuilder();
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                var sheet = workbook.getSheetAt(sheetIndex);
                appendLine(text, "[Sheet: " + sheet.getSheetName() + "]");
                for (Row row : sheet) {
                    StringBuilder line = new StringBuilder();
                    for (int cellIndex = 0; cellIndex < Math.max(0, row.getLastCellNum()); cellIndex++) {
                        Cell cell = row.getCell(cellIndex, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                        if (cell == null) continue;
                        String value;
                        try {
                            value = formatter.formatCellValue(cell, evaluator).trim();
                        } catch (RuntimeException ex) {
                            value = formatter.formatCellValue(cell).trim();
                        }
                        if (!value.isBlank()) {
                            if (!line.isEmpty()) line.append("\t");
                            line.append("[").append(sheet.getSheetName()).append("!")
                                    .append(org.apache.poi.ss.util.CellReference.convertNumToColString(cellIndex))
                                    .append(row.getRowNum() + 1).append("] ").append(value);
                        }
                    }
                    appendLine(text, line.toString());
                }
            }
            return text.toString().trim();
        } catch (IOException | RuntimeException ex) {
            throw new IllegalArgumentException("Failed to read XLS content: " + ex.getMessage(), ex);
        }
    }

    private static String extractPpt(byte[] bytes) {
        try (var input = new ByteArrayInputStream(bytes); var slideshow = new HSLFSlideShow(input)) {
            StringBuilder text = new StringBuilder();
            int slideNumber = 1;
            for (HSLFSlide slide : slideshow.getSlides()) {
                appendLine(text, "[Slide " + slideNumber++ + "]");
                for (HSLFShape shape : slide.getShapes()) {
                    if (shape instanceof HSLFTextShape textShape) appendLine(text, textShape.getText());
                }
            }
            return text.toString().trim();
        } catch (IOException | RuntimeException ex) {
            throw new IllegalArgumentException("Failed to read PPT content: " + ex.getMessage(), ex);
        }
    }

    private static void appendLine(StringBuilder text, String value) {
        if (value == null || value.isBlank()) return;
        if (!text.isEmpty()) text.append('\n');
        text.append(value.trim());
    }
}
