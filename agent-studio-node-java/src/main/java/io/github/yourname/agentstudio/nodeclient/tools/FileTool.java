package io.github.yourname.agentstudio.nodeclient.tools;

import io.github.yourname.agentstudio.nodeclient.runtime.ToolExecutionResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** File operations constrained to a node's configured workspace. */
public final class FileTool {

    private static final int MAX_READ_BYTES = 1_024 * 1_024;
    private static final int MAX_LIST_ENTRIES = 200;
    private static final int MAX_SEARCH_QUERY_CHARS = 512;
    private static final int MAX_SEARCH_RESULTS = 200;
    private static final int MAX_SEARCH_FILES = 3_000;
    private static final int MAX_SEARCH_FILE_BYTES = 1_024 * 1_024;
    private static final int MAX_SEARCH_LINE_CHARS = 500;

    private final Path workspaceRoot;

    public FileTool(Path workspaceRoot) {
        try {
            if (workspaceRoot == null || !Files.isDirectory(workspaceRoot)) {
                throw new IllegalArgumentException("Workspace must be an existing directory: " + workspaceRoot);
            }
            this.workspaceRoot = workspaceRoot.toRealPath();
        } catch (IOException ex) {
            throw new IllegalArgumentException("Cannot resolve workspace: " + workspaceRoot, ex);
        }
    }

    public ToolExecutionResult list(Map<String, Object> arguments) {
        try {
            Path directory = resolveExisting(value(arguments, "path"), true);
            List<Map<String, Object>> entries;
            try (var stream = Files.list(directory)) {
                entries = stream
                        .sorted(Comparator.comparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                        .limit(MAX_LIST_ENTRIES)
                        .map(this::entry)
                        .toList();
            }
            return ToolExecutionResult.success(Map.of(
                    "path", directory.toString(),
                    "entries", entries,
                    "truncated", entries.size() == MAX_LIST_ENTRIES));
        } catch (Exception ex) {
            return ToolExecutionResult.failure("fs.list failed: " + message(ex));
        }
    }

    public ToolExecutionResult read(Map<String, Object> arguments) {
        try {
            Path file = resolveExisting(value(arguments, "path"), false);
            if (!Files.isRegularFile(file)) {
                return ToolExecutionResult.failure("fs.read requires a regular file: " + file);
            }
            long size = Files.size(file);
            byte[] bytes;
            try (var stream = Files.newInputStream(file)) {
                bytes = stream.readNBytes(MAX_READ_BYTES + 1);
            }
            boolean truncated = bytes.length > MAX_READ_BYTES;
            int length = truncated ? MAX_READ_BYTES : bytes.length;
            String content = new String(bytes, 0, length, StandardCharsets.UTF_8);
            return ToolExecutionResult.success(Map.of(
                    "path", file.toString(),
                    "content", content,
                    "sizeBytes", size,
                    "truncated", truncated));
        } catch (Exception ex) {
            return ToolExecutionResult.failure("fs.read failed: " + message(ex));
        }
    }

    /** Searches UTF-8 text files without following links or traversing dependency/output directories. */
    public ToolExecutionResult search(Map<String, Object> arguments) {
        String query = value(arguments, "query");
        if (query == null || query.isBlank()) {
            return ToolExecutionResult.failure("Missing required argument: query");
        }
        if (query.length() > MAX_SEARCH_QUERY_CHARS || query.indexOf('\n') >= 0 || query.indexOf('\r') >= 0) {
            return ToolExecutionResult.failure("fs.search query must be one line and at most " + MAX_SEARCH_QUERY_CHARS + " characters.");
        }
        try {
            Path directory = resolveExisting(value(arguments, "path"), true);
            boolean caseSensitive = booleanValue(arguments, "caseSensitive", false);
            int maxResults = boundedInteger(arguments, "maxResults", 80, 1, MAX_SEARCH_RESULTS);
            String needle = caseSensitive ? query : query.toLowerCase(java.util.Locale.ROOT);
            List<Map<String, Object>> matches = new ArrayList<>();
            int scannedFiles = 0;
            boolean truncated = false;

            int[] scanned = {0};
            boolean[] limited = {false};
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path current, BasicFileAttributes attributes) {
                    return current.equals(directory) || !ignoredSearchPath(current)
                            ? FileVisitResult.CONTINUE
                            : FileVisitResult.SKIP_SUBTREE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    if (Files.isSymbolicLink(file) || !attributes.isRegularFile()) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (++scanned[0] > MAX_SEARCH_FILES) {
                        limited[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    if (attributes.size() > MAX_SEARCH_FILE_BYTES) {
                        return FileVisitResult.CONTINUE;
                    }
                    byte[] bytes = Files.readAllBytes(file);
                    if (containsNul(bytes)) {
                        return FileVisitResult.CONTINUE;
                    }
                    String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\\R", -1);
                    for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
                        String comparable = caseSensitive ? lines[lineIndex] : lines[lineIndex].toLowerCase(java.util.Locale.ROOT);
                        if (comparable.contains(needle)) {
                            matches.add(searchMatch(file, lineIndex + 1, lines[lineIndex]));
                            if (matches.size() >= maxResults) {
                                limited[0] = true;
                                return FileVisitResult.TERMINATE;
                            }
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException error) {
                    return FileVisitResult.CONTINUE;
                }
            });
            scannedFiles = scanned[0];
            truncated = limited[0];
            return ToolExecutionResult.success(Map.of(
                    "path", workspaceRelative(directory),
                    "query", query,
                    "matches", matches,
                    "scannedFiles", scannedFiles,
                    "truncated", truncated));
        } catch (Exception ex) {
            return ToolExecutionResult.failure("fs.search failed: " + message(ex));
        }
    }

    public ToolExecutionResult write(Map<String, Object> arguments) {
        String content = value(arguments, "content");
        if (content == null) {
            return ToolExecutionResult.failure("Missing required argument: content");
        }
        try {
            Path file = resolveForWrite(value(arguments, "path"));
            boolean existed = Files.exists(file);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            return ToolExecutionResult.success(Map.of(
                    "path", file.toString(),
                    "created", !existed,
                    "sizeBytes", Files.size(file)));
        } catch (Exception ex) {
            return ToolExecutionResult.failure("fs.write failed: " + message(ex));
        }
    }

    /** Applies one unambiguous literal replacement to a UTF-8 file. */
    public ToolExecutionResult applyPatch(Map<String, Object> arguments) {
        String expected = value(arguments, "expected");
        String replacement = value(arguments, "replacement");
        if (expected == null || expected.isEmpty()) {
            return ToolExecutionResult.failure("Missing required argument: expected");
        }
        if (replacement == null) {
            return ToolExecutionResult.failure("Missing required argument: replacement");
        }
        try {
            Path file = resolveExisting(value(arguments, "path"), false);
            if (!Files.isRegularFile(file)) {
                return ToolExecutionResult.failure("fs.apply_patch requires a regular file: " + file);
            }
            String original = Files.readString(file, StandardCharsets.UTF_8);
            int first = original.indexOf(expected);
            if (first < 0) {
                return ToolExecutionResult.failure("Patch target was not found in " + file);
            }
            if (original.indexOf(expected, first + expected.length()) >= 0) {
                return ToolExecutionResult.failure("Patch target is ambiguous in " + file);
            }
            String patched = original.substring(0, first) + replacement + original.substring(first + expected.length());
            Files.writeString(file, patched, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            return ToolExecutionResult.success(Map.of(
                    "path", file.toString(),
                    "replacements", 1,
                    "sizeBytes", Files.size(file)));
        } catch (Exception ex) {
            return ToolExecutionResult.failure("fs.apply_patch failed: " + message(ex));
        }
    }

    private Path resolveExisting(String requested, boolean directory) throws IOException {
        Path candidate = candidate(requested);
        if (!Files.exists(candidate)) {
            throw new IllegalArgumentException("Path does not exist: " + candidate);
        }
        Path realPath = candidate.toRealPath();
        if (!realPath.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("Path must stay inside the configured workspace.");
        }
        if (directory && !Files.isDirectory(realPath)) {
            throw new IllegalArgumentException("Path is not a directory: " + realPath);
        }
        return realPath;
    }

    private Path resolveForWrite(String requested) throws IOException {
        Path candidate = candidate(requested);
        Path parent = candidate.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("A file path is required.");
        }
        Path existingParent = parent;
        while (!Files.exists(existingParent)) {
            existingParent = existingParent.getParent();
            if (existingParent == null) {
                throw new IllegalArgumentException("Path has no existing workspace parent.");
            }
        }
        if (!existingParent.toRealPath().startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("Path must stay inside the configured workspace.");
        }
        if (Files.exists(candidate) && !candidate.toRealPath().startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("Path must stay inside the configured workspace.");
        }
        return candidate;
    }

    private Path candidate(String requested) {
        if (requested == null || requested.isBlank()) {
            throw new IllegalArgumentException("Missing required argument: path");
        }
        Path path = Path.of(requested);
        if (!path.isAbsolute()) {
            path = workspaceRoot.resolve(path);
        }
        path = path.normalize();
        if (!path.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("Path must stay inside the configured workspace.");
        }
        return path;
    }

    private Map<String, Object> searchMatch(Path file, int lineNumber, String line) {
        return Map.of(
                "path", workspaceRelative(file),
                "line", lineNumber,
                "text", preview(line));
    }

    private String workspaceRelative(Path path) {
        return workspaceRoot.relativize(path).toString().replace('\\', '/');
    }

    private boolean ignoredSearchPath(Path path) {
        Path relative = workspaceRoot.relativize(path);
        for (Path segment : relative) {
            String name = segment.toString();
            if (".git".equals(name)
                    || ".gradle".equals(name)
                    || "node_modules".equals(name)
                    || "build".equals(name)
                    || "target".equals(name)
                    || "out".equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsNul(byte[] bytes) {
        for (byte value : bytes) {
            if (value == 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean booleanValue(Map<String, Object> arguments, String key, boolean fallback) {
        String value = value(arguments, key);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    private static int boundedInteger(Map<String, Object> arguments, String key, int fallback, int min, int max) {
        String value = value(arguments, key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Math.max(min, Math.min(Integer.parseInt(value), max));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(key + " must be an integer.");
        }
    }

    private static String preview(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.length() <= MAX_SEARCH_LINE_CHARS
                ? normalized
                : normalized.substring(0, MAX_SEARCH_LINE_CHARS) + "...";
    }

    private Map<String, Object> entry(Path path) {
        try {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("name", path.getFileName().toString());
            result.put("path", path.toRealPath().toString());
            result.put("type", Files.isDirectory(path) ? "directory" : "file");
            if (Files.isRegularFile(path)) {
                result.put("sizeBytes", Files.size(path));
            }
            return result;
        } catch (IOException ex) {
            return Map.of("name", path.getFileName().toString(), "type", "unreadable");
        }
    }

    private static String value(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        return value == null ? null : value.toString();
    }

    private static String message(Exception ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }
}
