package io.github.yourname.agentstudio.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.apache.poi.xslf.usermodel.XSLFGroupShape;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

class OfficeDocumentTextExtractorTest {

    @Test
    void extractsParagraphsAndTablesFromDocx() throws Exception {
        byte[] bytes;
        try (var document = new XWPFDocument(); var output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("Quarterly planning");
            var row = document.createTable(1, 2).getRow(0);
            row.getCell(0).setText("Owner");
            row.getCell(1).setText("Mina");
            document.write(output);
            bytes = output.toByteArray();
        }

        assertThat(OfficeDocumentTextExtractor.extract("plan.docx", bytes))
                .contains("Quarterly planning", "Owner | Mina");
    }

    @Test
    void extractsHeadersAlongsideDocumentBodyAndTables() throws Exception {
        byte[] bytes;
        try (var document = new XWPFDocument(); var output = new ByteArrayOutputStream()) {
            document.createHeader(HeaderFooterType.DEFAULT).createParagraph().createRun().setText("Internal only");
            document.createParagraph().createRun().setText("Launch plan");
            document.write(output);
            bytes = output.toByteArray();
        }

        assertThat(OfficeDocumentTextExtractor.extract("plan.docx", bytes))
                .contains("Internal only", "Launch plan");
    }

    @Test
    void preservesSheetNamesAndFormulaValuesFromXlsx() throws Exception {
        byte[] bytes;
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Sales");
            sheet.createRow(0).createCell(0).setCellValue("Revenue");
            sheet.createRow(1).createCell(0).setCellValue(1200);
            sheet.getRow(1).createCell(1).setCellFormula("A2*2");
            workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();
            workbook.write(output);
            bytes = output.toByteArray();
        }

        assertThat(OfficeDocumentTextExtractor.extract("report.xlsx", bytes))
                .contains("[Sheet: Sales]", "Revenue", "1200", "2400");
    }

    @Test
    void preservesSheetAndRowLocationsForMergedAndCrossSheetFormulas() throws Exception {
        byte[] bytes;
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sales = workbook.createSheet("Sales");
            sales.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, 2));
            sales.createRow(0).createCell(0).setCellValue("Regional report");
            sales.createRow(1).createCell(0).setCellValue(1200);
            sales.getRow(1).createCell(1).setCellValue(800);
            sales.getRow(1).createCell(2).setCellFormula("A2-B2");
            var summary = workbook.createSheet("Summary");
            summary.createRow(0).createCell(0).setCellValue("Profit from Sales");
            summary.createRow(1).createCell(0).setCellFormula("Sales!C2");
            workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();
            workbook.write(output);
            bytes = output.toByteArray();
        }

        assertThat(OfficeDocumentTextExtractor.extract("report.xlsx", bytes))
                .contains("[Sheet: Sales]", "[Sales!A1]", "[Summary!A1]", "[Summary!A2]", "Regional report", "400");
    }

    @Test
    void preservesSlideBoundariesAndTextFromPptx() throws Exception {
        byte[] bytes;
        try (var slideshow = new XMLSlideShow(); var output = new ByteArrayOutputStream()) {
            var slide = slideshow.createSlide();
            XSLFTextBox box = slide.createTextBox();
            box.setText("Launch review");
            slideshow.write(output);
            bytes = output.toByteArray();
        }

        assertThat(OfficeDocumentTextExtractor.extract("review.pptx", bytes))
                .contains("[Slide 1]", "Launch review");
    }

    @Test
    void extractsGroupedTextAndTableCellsFromPptx() throws Exception {
        byte[] bytes;
        try (var slideshow = new XMLSlideShow(); var output = new ByteArrayOutputStream()) {
            var slide = slideshow.createSlide();
            XSLFGroupShape group = slide.createGroup();
            group.createTextBox().setText("Grouped decision");
            XSLFTable table = slide.createTable();
            XSLFTableRow row = table.addRow();
            row.addCell().setText("Owner");
            row.addCell().setText("Mina");
            slideshow.write(output);
            bytes = output.toByteArray();
        }

        assertThat(OfficeDocumentTextExtractor.extract("review.pptx", bytes))
                .contains("Grouped decision", "Owner | Mina");
    }
}
