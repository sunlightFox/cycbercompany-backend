package io.github.yourname.cycbercompany.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import org.apache.poi.hslf.usermodel.HSLFTextBox;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.junit.jupiter.api.Test;

class LegacyOfficeDocumentTextExtractorTest {

    @Test
    void extractsLegacyWordText() throws Exception {
        byte[] bytes;
        try (InputStream input = getClass().getResourceAsStream("/legacy-sample.doc")) {
            assertThat(input).as("legacy DOC fixture").isNotNull();
            bytes = input.readAllBytes();
        }

        assertThat(LegacyOfficeDocumentTextExtractor.extract("legacy.doc", bytes))
                .isNotBlank();
    }

    @Test
    void extractsLegacyWorkbookCellsAndSheetLocations() throws Exception {
        byte[] bytes;
        try (var workbook = new HSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Sales");
            sheet.createRow(0).createCell(0).setCellValue("Profit");
            sheet.createRow(1).createCell(0).setCellValue(400);
            workbook.write(output);
            bytes = output.toByteArray();
        }

        assertThat(LegacyOfficeDocumentTextExtractor.extract("legacy.xls", bytes))
                .contains("[Sheet: Sales]", "[Sales!A2] 400");
    }

    @Test
    void extractsLegacyWorkbookFormulaResultAtItsCellCoordinate() throws Exception {
        byte[] bytes;
        try (var workbook = new HSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Summary");
            var row = sheet.createRow(0);
            row.createCell(0).setCellValue(1200);
            row.createCell(1).setCellValue(800);
            row.createCell(2).setCellFormula("A1-B1");
            workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();
            workbook.write(output);
            bytes = output.toByteArray();
        }

        assertThat(LegacyOfficeDocumentTextExtractor.extract("formula.xls", bytes))
                .contains("[Summary!C1] 400");
    }

    @Test
    void extractsRealWorldLegacyWorkbookFixtureWithCoordinates() throws Exception {
        assertThat(LegacyOfficeDocumentTextExtractor.extract("Simple.xls", resource("/Simple.xls")))
                .contains("[Sheet: Sheet1]", "[Sheet1!A1] replaceMe");
    }

    @Test
    void extractsLegacyPresentationText() throws Exception {
        byte[] bytes;
        try (var slideshow = new HSLFSlideShow(); var output = new ByteArrayOutputStream()) {
            var slide = slideshow.createSlide();
            var box = new HSLFTextBox();
            box.setText("Legacy review");
            slide.addShape(box);
            slideshow.write(output);
            bytes = output.toByteArray();
        }

        assertThat(LegacyOfficeDocumentTextExtractor.extract("legacy.ppt", bytes))
                .contains("[Slide 1]", "Legacy review");
    }

    @Test
    void extractsRealWorldLegacyPresentationFixtureText() throws Exception {
        assertThat(LegacyOfficeDocumentTextExtractor.extract("WithComments.ppt", resource("/WithComments.ppt")))
                .contains("[Slide 1]", "Test Slide", "With a comment on it");
    }

    private static byte[] resource(String name) throws Exception {
        try (InputStream input = LegacyOfficeDocumentTextExtractorTest.class.getResourceAsStream(name)) {
            assertThat(input).as("fixture %s", name).isNotNull();
            return input.readAllBytes();
        }
    }
}
