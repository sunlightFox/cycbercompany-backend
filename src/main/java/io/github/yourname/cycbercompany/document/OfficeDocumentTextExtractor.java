package io.github.yourname.cycbercompany.document;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFGroupShape;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFHeaderFooter;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

/** Extracts a readable, location-preserving text representation from Office Open XML files. */
public final class OfficeDocumentTextExtractor {

    private OfficeDocumentTextExtractor() {
    }

    public static boolean supports(String sourceName) {
        String name = sourceName == null ? "" : sourceName.toLowerCase(Locale.ROOT);
        return name.endsWith(".docx") || name.endsWith(".xlsx") || name.endsWith(".pptx")
                || LegacyOfficeDocumentTextExtractor.supports(sourceName);
    }

    public static String extract(String sourceName, byte[] bytes) {
        String name = sourceName == null ? "" : sourceName.toLowerCase(Locale.ROOT);
        if (LegacyOfficeDocumentTextExtractor.supports(sourceName)) {
            return LegacyOfficeDocumentTextExtractor.extract(sourceName, bytes);
        }
        if (name.endsWith(".docx")) {
            return extractDocx(bytes);
        }
        if (name.endsWith(".xlsx")) {
            return extractXlsx(bytes);
        }
        if (name.endsWith(".pptx")) {
            return extractPptx(bytes);
        }
        throw new IllegalArgumentException("Unsupported Office file type: " + sourceName);
    }

    private static String extractDocx(byte[] bytes) {
        try (var input = new ByteArrayInputStream(bytes); var document = new XWPFDocument(input)) {
            StringBuilder text = new StringBuilder();
            for (XWPFHeaderFooter header : document.getHeaderList()) {
                appendWordBody(text, header.getBodyElements());
            }
            appendWordBody(text, document.getBodyElements());
            for (XWPFHeaderFooter footer : document.getFooterList()) {
                appendWordBody(text, footer.getBodyElements());
            }
            return text.toString().trim();
        } catch (IOException ex) {
            throw new IllegalArgumentException("Failed to read DOCX content: " + ex.getMessage(), ex);
        }
    }

    private static String extractXlsx(byte[] bytes) {
        try (var input = new ByteArrayInputStream(bytes); var workbook = new XSSFWorkbook(input)) {
            StringBuilder text = new StringBuilder();
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                XSSFSheet sheet = workbook.getSheetAt(sheetIndex);
                appendLine(text, "[Sheet: " + sheet.getSheetName() + "]");
                sheet.getMergedRegions().forEach(range -> appendLine(text, "[Merged: " + range.formatAsString() + "]"));
                for (Row row : sheet) {
                    StringBuilder line = new StringBuilder();
                    short lastCell = row.getLastCellNum();
                    for (int cellIndex = 0; cellIndex < Math.max(0, lastCell); cellIndex++) {
                        if (cellIndex > 0) {
                            line.append("\t");
                        }
                        Cell cell = row.getCell(cellIndex, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                        if (cell != null) {
                            String value = formatCell(formatter, evaluator, cell);
                            if (!value.isBlank()) {
                                line.append("[")
                                        .append(sheet.getSheetName())
                                        .append("!")
                                        .append(CellReference.convertNumToColString(cellIndex))
                                        .append(row.getRowNum() + 1)
                                        .append("] ")
                                        .append(value);
                            }
                        }
                    }
                    appendLine(text, "[" + sheet.getSheetName() + "!" + (row.getRowNum() + 1) + "] " + line);
                }
            }
            return text.toString().trim();
        } catch (IOException ex) {
            throw new IllegalArgumentException("Failed to read XLSX content: " + ex.getMessage(), ex);
        }
    }

    private static String extractPptx(byte[] bytes) {
        try (var input = new ByteArrayInputStream(bytes); var slideshow = new XMLSlideShow(input)) {
            StringBuilder text = new StringBuilder();
            int slideNumber = 1;
            for (XSLFSlide slide : slideshow.getSlides()) {
                appendLine(text, "[Slide " + slideNumber++ + "]");
                for (XSLFShape shape : slide.getShapes()) {
                    appendSlideShape(text, shape);
                }
            }
            return text.toString().trim();
        } catch (IOException ex) {
            throw new IllegalArgumentException("Failed to read PPTX content: " + ex.getMessage(), ex);
        }
    }

    private static void appendWordBody(StringBuilder text, java.util.List<IBodyElement> elements) {
        for (IBodyElement element : elements) {
            if (element instanceof XWPFParagraph paragraph) {
                appendLine(text, paragraph.getText());
            } else if (element instanceof XWPFTable table) {
                appendWordTable(text, table);
            }
        }
    }

    private static void appendWordTable(StringBuilder text, XWPFTable table) {
        for (XWPFTableRow row : table.getRows()) {
            String line = row.getTableCells().stream()
                    .map(cell -> {
                        StringBuilder value = new StringBuilder();
                        appendWordBody(value, cell.getBodyElements());
                        return value.toString().replaceAll("\\s+", " ").trim();
                    })
                    .filter(value -> !value.isBlank())
                    .reduce((left, right) -> left + " | " + right)
                    .orElse("");
            appendLine(text, line);
        }
    }

    private static String formatCell(DataFormatter formatter, FormulaEvaluator evaluator, Cell cell) {
        try {
            return formatter.formatCellValue(cell, evaluator).trim();
        } catch (RuntimeException ex) {
            return formatter.formatCellValue(cell).trim();
        }
    }

    private static void appendSlideShape(StringBuilder text, XSLFShape shape) {
        if (shape instanceof XSLFGroupShape group) {
            for (XSLFShape child : group.getShapes()) {
                appendSlideShape(text, child);
            }
        } else if (shape instanceof XSLFTable table) {
            for (XSLFTableRow row : table.getRows()) {
                String line = row.getCells().stream()
                        .map(cell -> cell.getText().replaceAll("\\s+", " ").trim())
                        .filter(value -> !value.isBlank())
                        .reduce((left, right) -> left + " | " + right)
                        .orElse("");
                appendLine(text, line);
            }
        } else if (shape instanceof XSLFTextShape textShape) {
            appendLine(text, textShape.getText());
        }
    }

    private static void appendLine(StringBuilder text, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!text.isEmpty()) {
            text.append('\n');
        }
        text.append(value.trim());
    }
}
