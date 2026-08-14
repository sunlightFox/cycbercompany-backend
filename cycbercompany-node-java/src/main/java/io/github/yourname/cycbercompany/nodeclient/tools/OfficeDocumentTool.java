package io.github.yourname.cycbercompany.nodeclient.tools;

import io.github.yourname.cycbercompany.nodeclient.runtime.ToolExecutionResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

/** Bounded DOCX/XLSX handling for explicitly approved local-office requests. */
public final class OfficeDocumentTool {
    private static final long MAX_BYTES = 25L * 1024 * 1024;
    private static final int MAX_TEXT_CHARS = 200_000;
    private final Path artifactRoot;

    public OfficeDocumentTool(Path artifactRoot) {
        this.artifactRoot = artifactRoot.toAbsolutePath().normalize();
    }

    public ToolExecutionResult read(Map<String, Object> arguments) {
        try {
            Path file = existingPath(arguments);
            String extension = extension(file);
            String content = switch (extension) {
                case "docx" -> readDocx(file);
                case "xlsx" -> readXlsx(file);
                default -> throw new IllegalArgumentException("Only .docx and .xlsx files are supported.");
            };
            return ToolExecutionResult.success(Map.of("filename", file.getFileName().toString(), "format", extension, "content", truncate(content)));
        } catch (Exception ex) {
            return ToolExecutionResult.failure("system.office.read failed: " + message(ex));
        }
    }

    public ToolExecutionResult create(String runId, Map<String, Object> arguments) {
        try {
            Path destination = destination(arguments);
            String format = extension(destination);
            if (!format.equals("docx") && !format.equals("xlsx")) throw new IllegalArgumentException("Destination must end in .docx or .xlsx.");
            String content = text(arguments, "content");
            String title = optionalText(arguments, "title");
            Files.createDirectories(destination.getParent());
            if (Files.exists(destination)) throw new IllegalArgumentException("Destination already exists; choose a new path.");
            if (format.equals("docx")) writeDocx(destination, title, content); else writeXlsx(destination, title, content);
            Path delivery = artifactPath(runId, destination.getFileName().toString());
            Files.createDirectories(delivery.getParent());
            Files.copy(destination, delivery, StandardCopyOption.REPLACE_EXISTING);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("saved", true);
            result.put("filename", destination.getFileName().toString());
            result.put("format", format);
            result.put("artifactType", "office-document");
            result.put("mimeType", format.equals("docx") ? "application/vnd.openxmlformats-officedocument.wordprocessingml.document" : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            result.put("artifactPath", artifactRoot.relativize(delivery).toString().replace('\\', '/'));
            return ToolExecutionResult.success(result);
        } catch (Exception ex) {
            return ToolExecutionResult.failure("system.office.create failed: " + message(ex));
        }
    }

    private static String readDocx(Path file) throws IOException {
        try (XWPFDocument document = new XWPFDocument(Files.newInputStream(file))) {
            return document.getParagraphs().stream().map(paragraph -> paragraph.getText()).reduce("", (left, right) -> left + right + "\n");
        }
    }

    private static String readXlsx(Path file) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(Files.newInputStream(file))) {
            StringBuilder result = new StringBuilder();
            for (var sheet : workbook) {
                result.append("# ").append(sheet.getSheetName()).append('\n');
                for (var row : sheet) {
                    for (int cell = 0; cell < row.getLastCellNum(); cell++) {
                        if (cell > 0) result.append('\t');
                        var value = row.getCell(cell);
                        if (value != null) result.append(value.toString());
                    }
                    result.append('\n');
                }
            }
            return result.toString();
        }
    }

    private static void writeDocx(Path output, String title, String content) throws IOException {
        try (XWPFDocument document = new XWPFDocument(); var stream = Files.newOutputStream(output)) {
            if (!title.isBlank()) document.createParagraph().createRun().setText(title);
            for (String line : content.split("\\R", -1)) document.createParagraph().createRun().setText(line);
            document.write(stream);
        }
    }

    private static void writeXlsx(Path output, String title, String content) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); var stream = Files.newOutputStream(output)) {
            var sheet = workbook.createSheet("Report"); int rowNumber = 0;
            if (!title.isBlank()) sheet.createRow(rowNumber++).createCell(0).setCellValue(title);
            for (String line : content.split("\\R", -1)) {
                var row = sheet.createRow(rowNumber++); String[] cells = line.split("\\t", -1);
                for (int index = 0; index < cells.length; index++) row.createCell(index).setCellValue(cells[index]);
            }
            for (int index = 0; index < 12; index++) sheet.autoSizeColumn(index);
            workbook.write(stream);
        }
    }

    private Path artifactPath(String runId, String filename) {
        return artifactRoot.resolve("office").resolve(runId == null || runId.isBlank() ? "manual" : runId)
                .resolve(UUID.randomUUID() + "-" + filename).normalize();
    }

    private static Path existingPath(Map<String, Object> arguments) throws IOException {
        Path path = Path.of(text(arguments, "path")).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path) || Files.size(path) > MAX_BYTES) throw new IllegalArgumentException("Path must be an existing Office file up to 25 MB.");
        return path;
    }
    private static Path destination(Map<String, Object> arguments) {
        Path path = Path.of(text(arguments, "path")).toAbsolutePath().normalize();
        if (path.getParent() == null) throw new IllegalArgumentException("Destination must include a parent directory.");
        return path;
    }
    private static String extension(Path path) { String name = path.getFileName().toString(); int dot = name.lastIndexOf('.'); return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(); }
    private static String text(Map<String, Object> arguments, String key) { Object value = arguments.get(key); if (!(value instanceof String text) || text.isBlank()) throw new IllegalArgumentException(key + " is required."); return text; }
    private static String optionalText(Map<String, Object> arguments, String key) { Object value = arguments.get(key); return value instanceof String text ? text : ""; }
    private static String truncate(String value) { return value.length() <= MAX_TEXT_CHARS ? value : value.substring(0, MAX_TEXT_CHARS) + "\n[truncated]"; }
    private static String message(Exception ex) { return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(); }
}
