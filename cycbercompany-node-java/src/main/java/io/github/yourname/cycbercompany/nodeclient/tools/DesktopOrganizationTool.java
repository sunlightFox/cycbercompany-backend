package io.github.yourname.cycbercompany.nodeclient.tools;

import io.github.yourname.cycbercompany.nodeclient.runtime.ToolExecutionResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * File operations intentionally confined to the current user's desktop.
 *
 * <p>Unlike the generic system filesystem tools, these operations never accept an absolute path.
 * A model can inspect the desktop, create one top-level category, and move one top-level regular
 * file into that category. This keeps desktop organization useful without turning it into a
 * general-purpose system filesystem capability.
 */
public final class DesktopOrganizationTool {

    private static final int MAX_TEXT_BYTES = 256 * 1024;
    private final Path desktopRoot;
    private final FileTool files;

    public DesktopOrganizationTool(Path desktopRoot) {
        try {
            if (desktopRoot == null || !Files.isDirectory(desktopRoot)) {
                throw new IllegalArgumentException("Desktop must be an existing directory: " + desktopRoot);
            }
            this.desktopRoot = desktopRoot.toRealPath();
            this.files = new FileTool(this.desktopRoot);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Cannot resolve desktop directory: " + desktopRoot, ex);
        }
    }

    public ToolExecutionResult list(Map<String, Object> arguments) {
        if (arguments != null && !arguments.isEmpty()) {
            return ToolExecutionResult.failure("desktop.organize.list does not accept a path or other arguments.");
        }
        ToolExecutionResult listed = files.list(Map.of("path", "."));
        if (!listed.success()) {
            return listed;
        }
        try {
            Map<String, Object> result = new LinkedHashMap<>(listed.result());
            result.put("desktopPath", desktopRoot.toString());
            result.put("sortableFiles", countSortableFiles());
            result.put("visibleDirectories", visibleDirectories());
            return ToolExecutionResult.success(result);
        } catch (IOException ex) {
            return ToolExecutionResult.failure("desktop.organize.list failed: " + message(ex));
        }
    }

    public ToolExecutionResult createCategory(Map<String, Object> arguments) {
        try {
            String category = requiredSegment(arguments, "category");
            return files.createDirectory(Map.of("path", category));
        } catch (Exception ex) {
            return ToolExecutionResult.failure("desktop.organize.mkdir failed: " + message(ex));
        }
    }

    /** Creates one new UTF-8 text file in the configured desktop root without overwriting. */
    public ToolExecutionResult write(Map<String, Object> arguments) {
        try {
            String filename = requiredSegment(arguments, "filename");
            String content = requiredContent(arguments);
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_TEXT_BYTES) {
                throw new IllegalArgumentException("content exceeds the " + MAX_TEXT_BYTES + " byte desktop text-file limit.");
            }
            Path target = desktopRoot.resolve(filename).normalize();
            if (!target.getParent().equals(desktopRoot) || Files.exists(target)) {
                throw new IllegalArgumentException("filename must name a new top-level desktop file.");
            }
            Files.write(target, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return ToolExecutionResult.success(Map.of("filename", filename, "created", true, "sizeBytes", bytes.length));
        } catch (Exception ex) {
            return ToolExecutionResult.failure("desktop.organize.write failed: " + message(ex));
        }
    }

    public ToolExecutionResult move(Map<String, Object> arguments) {
        try {
            String source = requiredSegment(arguments, "source");
            String category = requiredSegment(arguments, "category");
            Path sourcePath = desktopRoot.resolve(source).normalize();
            if (Files.isSymbolicLink(sourcePath) || !Files.isRegularFile(sourcePath)) {
                throw new IllegalArgumentException("source must name a top-level regular desktop file.");
            }
            if (Files.isHidden(sourcePath)) {
                throw new IllegalArgumentException("hidden desktop files cannot be organized.");
            }
            return files.move(Map.of(
                    "source", source,
                    "destination", category + "/" + source,
                    "replaceExisting", false));
        } catch (Exception ex) {
            return ToolExecutionResult.failure("desktop.organize.move failed: " + message(ex));
        }
    }

    /** Deletes one visible top-level desktop file. Directories, links, and hidden entries are never eligible. */
    public ToolExecutionResult delete(Map<String, Object> arguments) {
        try {
            String source = requiredSegment(arguments, "source");
            Path sourcePath = desktopRoot.resolve(source).normalize();
            if (Files.isDirectory(sourcePath) && !Files.isSymbolicLink(sourcePath)) {
                return ToolExecutionResult.failure(
                        "desktop.organize.delete does not delete directories. Stop retrying this tool; "
                                + "inspect the Desktop with system.desktop.organize.list, then after confirming "
                                + "the exact directory target use system.fs.delete with its desktopPath and "
                                + "recursive=true only when deleting its contents was explicitly requested.");
            }
            if (Files.isSymbolicLink(sourcePath) || !Files.isRegularFile(sourcePath)) {
                throw new IllegalArgumentException("source must name a top-level regular desktop file.");
            }
            if (Files.isHidden(sourcePath)) {
                throw new IllegalArgumentException("hidden desktop files cannot be deleted.");
            }
            Files.delete(sourcePath);
            return ToolExecutionResult.success(Map.of("source", source, "deleted", true));
        } catch (Exception ex) {
            return ToolExecutionResult.failure("desktop.organize.delete failed: " + message(ex));
        }
    }

    private int countSortableFiles() throws IOException {
        try (var entries = Files.list(desktopRoot)) {
            return (int) entries
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(Files::isRegularFile)
                    .filter(this::isVisible)
                    .count();
        }
    }

    private List<String> visibleDirectories() throws IOException {
        try (var entries = Files.list(desktopRoot)) {
            return entries
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(Files::isDirectory)
                    .filter(this::isVisible)
                    .map(path -> path.getFileName().toString())
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .limit(200)
                    .toList();
        }
    }

    private boolean isVisible(Path path) {
        try {
            return !Files.isHidden(path);
        } catch (IOException ex) {
            return false;
        }
    }

    private static String requiredSegment(Map<String, Object> arguments, String name) {
        Object value = arguments == null ? null : arguments.get(name);
        String text = value == null ? "" : value.toString().trim();
        if (text.isBlank()) {
            throw new IllegalArgumentException("Missing required argument: " + name);
        }
        String placeholderError = WindowsToolArgumentPolicy.placeholderError(text, name);
        if (placeholderError != null) {
            throw new IllegalArgumentException(placeholderError);
        }
        if (text.equals(".") || text.equals("..") || text.startsWith(".")
                || text.indexOf('/') >= 0 || text.indexOf('\\') >= 0 || text.indexOf(':') >= 0
                || text.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException(name + " must be one visible filename or category name.");
        }
        Path path = Path.of(text);
        if (path.isAbsolute() || path.getNameCount() != 1) {
            throw new IllegalArgumentException(name + " must be one visible filename or category name.");
        }
        return text;
    }

    private static String requiredContent(Map<String, Object> arguments) {
        Object value = arguments == null ? null : arguments.get("content");
        if (value == null) {
            throw new IllegalArgumentException("Missing required argument: content");
        }
        return value.toString();
    }

    private static String message(Exception ex) {
        return ex.getMessage() == null || ex.getMessage().isBlank()
                ? ex.getClass().getSimpleName()
                : ex.getMessage();
    }
}
