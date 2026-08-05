package io.github.yourname.agentstudio.nodeclient.tools;

import io.github.yourname.agentstudio.nodeclient.runtime.ToolExecutionResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** File operations constrained to a node's configured workspace. */
public final class FileTool {

    private static final int MAX_READ_BYTES = 1_024 * 1_024;
    private static final int MAX_READ_RANGE_LINES = 2_000;
    private static final int MAX_LIST_ENTRIES = 200;
    private static final int MAX_SEARCH_QUERY_CHARS = 512;
    private static final int MAX_SEARCH_RESULTS = 200;
    private static final int MAX_SEARCH_FILES = 3_000;
    private static final int MAX_SEARCH_FILE_BYTES = 1_024 * 1_024;
    private static final int MAX_SEARCH_LINE_CHARS = 500;

    private final Path workspaceRoot;
    private final boolean systemAccess;
    private final PatchFileMover patchFileMover;

    public FileTool(Path workspaceRoot) {
        this(workspaceRoot, false);
    }

    public FileTool(Path workspaceRoot, boolean systemAccess) {
        this(workspaceRoot, systemAccess, FileTool::moveReplacement);
    }

    /** 此构造器仅供同包测试替换文件移动行为，验证 I/O 失败时的恢复流程。 */
    FileTool(Path workspaceRoot, boolean systemAccess, PatchFileMover patchFileMover) {
        try {
            if (workspaceRoot == null || !Files.isDirectory(workspaceRoot)) {
                throw new IllegalArgumentException("Workspace must be an existing directory: " + workspaceRoot);
            }
            this.workspaceRoot = workspaceRoot.toRealPath();
            this.systemAccess = systemAccess;
            this.patchFileMover = Objects.requireNonNull(patchFileMover, "patchFileMover");
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
                    "path", displayPath(directory),
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
            Integer startLine = optionalPositiveInteger(arguments, "startLine");
            Integer endLine = optionalPositiveInteger(arguments, "endLine");
            if (startLine != null || endLine != null) {
                return readLineRange(file, startLine == null ? 1 : startLine, endLine);
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
                    "path", workspaceRelative(file),
                    "content", content,
                    // 摘要是模型下一次修改时的并发前置条件：读完文件后若被用户或另一个
                    // Agent 改过，写入/补丁会明确失败，而不是悄悄覆盖新内容。
                    "digest", sha256(Files.readAllBytes(file)),
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
            String expectedDigest = value(arguments, "expectedDigest");
            if (expectedDigest != null && !expectedDigest.isBlank()) {
                if (!existed) {
                    return ToolExecutionResult.failure("fs.write expected an existing file with digest " + expectedDigest);
                }
                requireDigest(file, expectedDigest);
            }
            // 先把完整内容写入目标文件同目录的临时文件，再一次替换目标文件。
            // 与“直接 TRUNCATE_EXISTING”相比，写盘失败时原文件仍然存在，不会留下半截源码。
            writeTextStaged(file, content, ".agent-studio-write-");
            return ToolExecutionResult.success(Map.of(
                    "path", workspaceRelative(file),
                    "created", !existed,
                    "digest", sha256(Files.readAllBytes(file)),
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
            String expectedDigest = value(arguments, "expectedDigest");
            if (expectedDigest != null && !expectedDigest.isBlank()) {
                requireDigest(file, expectedDigest);
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
            // 单文件补丁与批量补丁使用相同的“先暂存、后替换”原则，不能因为调用入口不同
            // 就退化成直接清空原文件再写入。
            writeTextStaged(file, patched, ".agent-studio-patch-");
            return ToolExecutionResult.success(Map.of(
                    "path", workspaceRelative(file),
                    "replacements", 1,
                    "digest", sha256(Files.readAllBytes(file)),
                    "sizeBytes", Files.size(file)));
        } catch (Exception ex) {
            return ToolExecutionResult.failure("fs.apply_patch failed: " + message(ex));
        }
    }

    /**
     * 先验证全部文件，再统一写入多文件补丁。
     *
     * <p>每个 change 都是 {path, expected, replacement, expectedDigest?}。任何一个文件
     * 发生摘要冲突、匹配缺失或匹配歧义时，方法在写入前失败，避免模型把半个重构提交到
     * 工作区。文件系统级移动仍可能因突发 I/O 失败中断，因此返回结果只在所有写入成功后
     * 标记成功，调用方必须按返回摘要继续验证。
     */
    public ToolExecutionResult applyPatchBatch(Map<String, Object> arguments) {
        Object rawChanges = arguments == null ? null : arguments.get("changes");
        if (!(rawChanges instanceof List<?> requested) || requested.isEmpty()) {
            return ToolExecutionResult.failure("fs.apply_patch_batch requires a non-empty changes array.");
        }
        if (requested.size() > 40) {
            return ToolExecutionResult.failure("fs.apply_patch_batch accepts at most 40 changes.");
        }
        try {
            Map<Path, String> originalContents = new LinkedHashMap<>();
            Map<Path, String> patchedContents = new LinkedHashMap<>();
            for (Object rawChange : requested) {
                if (!(rawChange instanceof Map<?, ?> map)) {
                    return ToolExecutionResult.failure("Each batch change must be an object.");
                }
                String path = stringValue(map.get("path"));
                String expected = stringValue(map.get("expected"));
                if (!map.containsKey("replacement") || map.get("replacement") == null) {
                    return ToolExecutionResult.failure("Each batch change requires replacement (it may be empty).");
                }
                String replacement = stringValue(map.get("replacement"));
                String expectedDigest = stringValue(map.get("expectedDigest"));
                if (path.isBlank() || expected.isEmpty()) {
                    return ToolExecutionResult.failure("Each batch change requires non-empty path and expected.");
                }
                Path file = resolveExisting(path, false);
                if (!Files.isRegularFile(file)) {
                    return ToolExecutionResult.failure("Batch patch paths must be regular files: " + path);
                }
                if (!expectedDigest.isBlank()) {
                    requireDigest(file, expectedDigest);
                }
                // 同一个文件允许在本批次中连续修改多处：后一个补丁基于前一个补丁的内存结果，
                // 但所有内容仍会在真正写盘前完成唯一匹配验证。
                String current = patchedContents.get(file);
                if (current == null) {
                    current = Files.readString(file, StandardCharsets.UTF_8);
                    originalContents.put(file, current);
                }
                int first = current.indexOf(expected);
                if (first < 0 || current.indexOf(expected, first + expected.length()) >= 0) {
                    return ToolExecutionResult.failure("Batch patch target must occur exactly once: " + path);
                }
                patchedContents.put(file, current.substring(0, first) + replacement + current.substring(first + expected.length()));
            }

            // 所有逻辑前置条件都已通过，随后才执行写入；使用同目录临时文件降低崩溃时
            // 留下截断目标文件的风险。
            Map<Path, Path> stagedFiles = new LinkedHashMap<>();
            Map<Path, Path> backups = new LinkedHashMap<>();
            List<Path> replacedFiles = new ArrayList<>();
            boolean rollbackSucceeded = true;
            try {
                // 先准备所有新内容和原文件备份。此阶段任意失败都还没有改动目标文件。
                for (Map.Entry<Path, String> entry : patchedContents.entrySet()) {
                    Path file = entry.getKey();
                    Path staged = Files.createTempFile(file.getParent(), ".agent-studio-patch-", ".tmp");
                    Files.writeString(staged, entry.getValue(), StandardCharsets.UTF_8,
                            StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                    stagedFiles.put(file, staged);
                    Path backup = Files.createTempFile(file.getParent(), ".agent-studio-patch-recovery-", ".bak");
                    Files.writeString(backup, originalContents.get(file), StandardCharsets.UTF_8,
                            StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                    backups.put(file, backup);
                }
                // 同目录移动通常是原子的；跨多个文件不能保证整体原子，因此配合下面的恢复流程。
                for (Path file : patchedContents.keySet()) {
                    patchFileMover.move(stagedFiles.get(file), file);
                    replacedFiles.add(file);
                }
            } catch (Exception writeFailure) {
                boolean rollbackAttempted = !replacedFiles.isEmpty();
                if (rollbackAttempted) {
                    // 已经替换的文件逐一从备份恢复。全部失败信息只保留数量，避免泄露节点绝对路径。
                    for (Path file : replacedFiles) {
                        try {
                            patchFileMover.move(backups.get(file), file);
                        } catch (Exception rollbackFailure) {
                            rollbackSucceeded = false;
                        }
                    }
                }
                List<String> recoveryFiles = rollbackSucceeded
                        ? List.of()
                        : backups.entrySet().stream()
                                .filter(entry -> Files.exists(entry.getValue()))
                                .map(entry -> workspaceRelative(entry.getValue()))
                                .toList();
                return ToolExecutionResult.failure(Map.of(
                                "rollbackAttempted", rollbackAttempted,
                                "rollbackSucceeded", rollbackSucceeded,
                                "replacedFileCount", replacedFiles.size(),
                                "recoveryFiles", recoveryFiles),
                        "fs.apply_patch_batch failed: " + message(writeFailure)
                                + "; rollback " + (rollbackAttempted
                                ? (rollbackSucceeded ? "succeeded" : "did not fully succeed")
                                : "was not needed"));
            } finally {
                // 成功或完全恢复后删除临时文件；恢复失败时保留备份，供人工安全恢复。
                deleteAll(stagedFiles.values());
                if (rollbackSucceeded) {
                    deleteAll(backups.values());
                }
            }
            List<Map<String, Object>> changed = patchedContents.keySet().stream()
                    .map(file -> Map.<String, Object>of(
                            "path", workspaceRelative(file),
                            "digest", sha256(readBytes(file)),
                            "sizeBytes", safeSize(file)))
                    .toList();
            return ToolExecutionResult.success(Map.of(
                    "changed", changed,
                    "replacements", requested.size(),
                    "writeMode", "staged_with_best_effort_rollback"));
        } catch (Exception ex) {
            return ToolExecutionResult.failure("fs.apply_patch_batch failed: " + message(ex));
        }
    }

    private static void deleteAll(Iterable<Path> files) {
        for (Path file : files) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // 清理失败不能掩盖已经成功写入的结果；操作系统会在后续清理临时文件。
            }
        }
    }

    /**
     * 在目标文件所在目录完成一次尽可能原子的文本替换。
     *
     * <p>临时文件必须与目标位于同一个目录：这样通常可使用同一文件系统的原子 rename，
     * 也不会把临时文件意外写进系统临时目录。少数文件系统不支持 ATOMIC_MOVE 时安全降级为
     * 普通同目录替换；即使降级，内容也已经全部写进临时文件，避免了直接截断目标文件。
     */
    private void writeTextStaged(Path file, String content, String temporaryPrefix) throws IOException {
        Path parent = file.getParent();
        if (parent == null) {
            throw new IOException("A workspace file must have a parent directory.");
        }
        Files.createDirectories(parent);
        Path staged = Files.createTempFile(parent, temporaryPrefix, ".tmp");
        try {
            Files.writeString(staged, content, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            patchFileMover.move(staged, file);
        } finally {
            // 成功替换后 staged 已被 move；替换失败时删除未使用的暂存文件，避免污染用户项目。
            Files.deleteIfExists(staged);
        }
    }

    /** 默认替换器优先请求文件系统原子移动，无法支持时才退回同目录替换。 */
    private static void moveReplacement(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @FunctionalInterface
    interface PatchFileMover {
        void move(Path source, Path target) throws IOException;
    }

    public ToolExecutionResult createDirectory(Map<String, Object> arguments) {
        try {
            Path directory = resolveForWrite(value(arguments, "path"));
            boolean existed = Files.isDirectory(directory);
            Files.createDirectories(directory);
            return ToolExecutionResult.success(Map.of(
                    "path", displayPath(directory),
                    "created", !existed));
        } catch (Exception ex) {
            return ToolExecutionResult.failure("fs.mkdir failed: " + message(ex));
        }
    }

    public ToolExecutionResult move(Map<String, Object> arguments) {
        try {
            Path source = resolveExisting(value(arguments, "source"), false);
            Path destination = resolveForWrite(value(arguments, "destination"));
            if (source.equals(destination)) {
                return ToolExecutionResult.failure("Source and destination must be different.");
            }
            if (Files.exists(destination) && !booleanValue(arguments, "replaceExisting", false)) {
                return ToolExecutionResult.failure("Destination already exists: " + destination);
            }
            boolean replaced = Files.exists(destination);
            Files.createDirectories(destination.getParent());
            if (replaced) {
                Files.move(source, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(source, destination);
            }
            return ToolExecutionResult.success(Map.of(
                    "source", displayPath(source),
                    "destination", displayPath(destination),
                    "replaced", replaced));
        } catch (Exception ex) {
            return ToolExecutionResult.failure("fs.move failed: " + message(ex));
        }
    }

    public ToolExecutionResult delete(Map<String, Object> arguments) {
        try {
            Path target = resolveExisting(value(arguments, "path"), false);
            if (target.getParent() == null) {
                return ToolExecutionResult.failure("Deleting a filesystem root is not allowed.");
            }
            boolean recursive = booleanValue(arguments, "recursive", false);
            if (Files.isDirectory(target) && !recursive) {
                Files.delete(target);
            } else if (Files.isDirectory(target)) {
                Files.walkFileTree(target, java.util.EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                        if (error != null) {
                            throw error;
                        }
                        Files.delete(directory);
                        return FileVisitResult.CONTINUE;
                    }
                });
            } else {
                Files.delete(target);
            }
            return ToolExecutionResult.success(Map.of("path", displayPath(target), "deleted", true, "recursive", recursive));
        } catch (Exception ex) {
            return ToolExecutionResult.failure("fs.delete failed: " + message(ex));
        }
    }

    private Path resolveExisting(String requested, boolean directory) throws IOException {
        Path candidate = candidate(requested);
        if (!Files.exists(candidate)) {
            throw new IllegalArgumentException("Path does not exist: " + candidate);
        }
        Path realPath = candidate.toRealPath();
        if (!isAllowed(realPath)) {
            throw new IllegalArgumentException("Path must stay inside the configured workspace.");
        }
        if (directory && !Files.isDirectory(realPath)) {
            throw new IllegalArgumentException("Path is not a directory: " + realPath);
        }
        return realPath;
    }

    private ToolExecutionResult readLineRange(Path file, int startLine, Integer requestedEndLine) throws IOException {
        int endLine = requestedEndLine == null ? startLine + 399 : requestedEndLine;
        if (endLine < startLine) {
            return ToolExecutionResult.failure("endLine must be greater than or equal to startLine.");
        }
        if (endLine - startLine + 1 > MAX_READ_RANGE_LINES) {
            return ToolExecutionResult.failure("Requested line range exceeds " + MAX_READ_RANGE_LINES + " lines.");
        }
        StringBuilder content = new StringBuilder();
        int actualEndLine = startLine - 1;
        boolean outputTruncated = false;
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            for (int lineNumber = 1; (line = reader.readLine()) != null; lineNumber++) {
                if (lineNumber < startLine) {
                    continue;
                }
                if (lineNumber > endLine) {
                    break;
                }
                if (content.length() + line.length() + 1 > MAX_READ_BYTES) {
                    outputTruncated = true;
                    break;
                }
                if (!content.isEmpty()) {
                    content.append('\n');
                }
                content.append(line);
                actualEndLine = lineNumber;
            }
        }
        if (actualEndLine < startLine) {
            return ToolExecutionResult.failure("Requested startLine is beyond the end of file: " + startLine);
        }
        return ToolExecutionResult.success(Map.of(
                "path", workspaceRelative(file),
                "content", content.toString(),
                "digest", sha256(Files.readAllBytes(file)),
                "startLine", startLine,
                "endLine", actualEndLine,
                "truncated", outputTruncated));
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
        if (!isAllowed(existingParent.toRealPath())) {
            throw new IllegalArgumentException("Path must stay inside the configured workspace.");
        }
        if (Files.exists(candidate) && !isAllowed(candidate.toRealPath())) {
            throw new IllegalArgumentException("Path must stay inside the configured workspace.");
        }
        return candidate;
    }

    private Path candidate(String requested) {
        if (requested == null || requested.isBlank()) {
            throw new IllegalArgumentException("Missing required argument: path");
        }
        rejectPlaceholderPath(requested, "path");
        Path path = Path.of(requested);
        if (!path.isAbsolute()) {
            path = workspaceRoot.resolve(path);
        }
        path = path.normalize();
        if (!isAllowed(path)) {
            throw new IllegalArgumentException("Path must stay inside the configured workspace.");
        }
        return path;
    }

    private static void rejectPlaceholderPath(String requested, String argumentName) {
        String normalized = requested.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        List<String> placeholders = List.of(
                "<path>",
                "<absolute path>",
                "<file>",
                "<folder>",
                "<directory>",
                "<dir>",
                "<cwd>",
                "<workspace>",
                "<project root>",
                "<desktop>",
                "<desktoppath>",
                "<desktop path>",
                "<destination>",
                "<source>");
        if (placeholders.stream().anyMatch(normalized::contains)) {
            throw new IllegalArgumentException(argumentName
                    + " contains an unreplaced placeholder. Use a concrete path returned by an inspection tool or provided by the user.");
        }
    }

    private Map<String, Object> searchMatch(Path file, int lineNumber, String line) {
        return Map.of(
                "path", workspaceRelative(file),
                "line", lineNumber,
                "text", preview(line));
    }

    private String workspaceRelative(Path path) {
        if (!path.startsWith(workspaceRoot)) {
            return path.toString();
        }
        String relative = workspaceRoot.relativize(path).toString().replace('\\', '/');
        return relative.isBlank() ? "." : relative;
    }

    private boolean isAllowed(Path path) {
        return systemAccess || path.startsWith(workspaceRoot);
    }

    private String displayPath(Path path) {
        return workspaceRelative(path);
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

    private static Integer optionalPositiveInteger(Map<String, Object> arguments, String key) {
        String value = value(arguments, key);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1) {
                throw new IllegalArgumentException(key + " must be a positive integer.");
            }
            return parsed;
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
            result.put("path", workspaceRelative(path));
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

    private static String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    /** 使用固定 SHA-256 表示文件版本，格式和服务端其他不可变快照保持一致。 */
    private static String sha256(byte[] bytes) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime.", ex);
        }
    }

    private static void requireDigest(Path file, String expectedDigest) throws IOException {
        String actualDigest = sha256(Files.readAllBytes(file));
        if (!MessageDigest.isEqual(
                actualDigest.getBytes(StandardCharsets.US_ASCII),
                expectedDigest.trim().getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalStateException("File changed after it was read; expected digest "
                    + expectedDigest + " but found " + actualDigest);
        }
    }

    private static byte[] readBytes(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read patched file " + path, ex);
        }
    }

    private static long safeSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot inspect patched file " + path, ex);
        }
    }

    private static String message(Exception ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }
}
