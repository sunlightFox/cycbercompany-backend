package io.github.yourname.agentstudio.nodeclient.tools;

import io.github.yourname.agentstudio.nodeclient.runtime.ToolExecutionResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 在指定工作区中识别常见项目，并给出可供后续工具执行的建议命令。
 *
 * <p>它<strong>不会</strong>执行构建、安装依赖或启动服务。这样编码代理先获得可靠的项目事实，
 * 再由需要审批的 {@code shell.run}/{@code process.start} 执行真正有副作用的操作。
 */
public final class ProjectTool {

    private static final int MAX_MANIFEST_BYTES = 128 * 1024;
    private static final int MAX_DISCOVERY_DEPTH = 4;
    private static final int MAX_DISCOVERED_PROJECTS = 40;
    /** 轻量索引必须有硬上限，不能为了找一个类把整个大仓库读入模型上下文。 */
    private static final int MAX_SYMBOL_DEPTH = 10;
    private static final int MAX_SYMBOL_FILES = 2_500;
    private static final int MAX_SYMBOL_FILE_BYTES = 512 * 1024;
    private static final int DEFAULT_MAX_SYMBOLS = 160;
    private static final int MAX_SYMBOLS = 400;
    private static final int MAX_SYMBOL_QUERY_CHARS = 160;
    /** 候选引用扫描与声明索引使用同一数量级的预算，防止重命名分析读穿大仓库。 */
    private static final int DEFAULT_MAX_REFERENCES = 200;
    private static final int MAX_REFERENCES = 400;
    private static final int MAX_REFERENCE_IDENTIFIER_CHARS = 160;
    private static final int MAX_DIAGNOSTIC_OUTPUT_CHARS = 48 * 1024;
    private static final int MAX_DIAGNOSTICS = 120;

    // 这是“导航索引”而不是编译器级 AST：只识别常见顶层声明，帮助模型快速决定下一次 fs.read 的目标。
    private static final Pattern JAVA_TYPE = Pattern.compile("^\\s*(?:(?:public|protected|private|abstract|final|static|sealed|non-sealed)\\s+)*(class|interface|enum|record)\\s+([A-Za-z_$][\\w$]*)");
    private static final Pattern JAVA_METHOD = Pattern.compile("^\\s*(?:(?:public|protected|private|static|final|abstract|synchronized|native|default)\\s+)*(?:[A-Za-z_$][\\w$<>?, \\[\\].]*?)\\s+([A-Za-z_$][\\w$]*)\\s*\\([^;{}]*\\)\\s*(?:throws\\s+[^\\{]+)?\\{");
    private static final Pattern KOTLIN_DECLARATION = Pattern.compile("^\\s*(?:(?:public|private|protected|internal|open|abstract|data|sealed|enum|annotation|suspend|override|inline|tailrec)\\s+)*(class|interface|object|fun)\\s+([A-Za-z_][\\w]*)");
    private static final Pattern WEB_DECLARATION = Pattern.compile("^\\s*(?:export\\s+)?(?:default\\s+)?(?:declare\\s+)?(?:async\\s+)?(class|interface|type|enum|function)\\s+([A-Za-z_$][\\w$]*)");
    private static final Pattern WEB_ARROW = Pattern.compile("^\\s*(?:export\\s+)?(?:const|let)\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*(?:async\\s*)?(?:\\([^)]*\\)|[A-Za-z_$][\\w$]*)\\s*=>");
    private static final Pattern PYTHON_DECLARATION = Pattern.compile("^\\s*(?:async\\s+)?(class|def)\\s+([A-Za-z_][\\w]*)");
    private static final Pattern GO_DECLARATION = Pattern.compile("^\\s*(func|type)\\s+(?:\\([^)]*\\)\\s+)?([A-Za-z_][\\w]*)");
    private static final Pattern RUST_DECLARATION = Pattern.compile("^\\s*(?:pub(?:\\([^)]*\\))?\\s+)?(struct|enum|trait|impl|fn)\\s+([A-Za-z_][\\w]*)");
    private static final Pattern LOCATION_WITH_COLUMNS = Pattern.compile("^\\s*(?:\\[ERROR\\]\\s*)?(.+?\\.(?:java|kt|kts|ts|tsx|js|jsx|py|go|rs)):(\\d+)(?::(\\d+))?\\s*:?\\s*(.*)$");
    private static final Pattern MAVEN_LOCATION = Pattern.compile("^\\s*\\[ERROR\\]\\s+(.+?):\\[(\\d+),(\\d+)\\]\\s*(.*)$");
    private static final Pattern TYPESCRIPT_LOCATION = Pattern.compile("^\\s*(.+?\\.(?:ts|tsx|js|jsx))\\((\\d+),(\\d+)\\):\\s*(error|warning)\\s*(?:TS\\d+)?\\s*:?[ ]*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern GRADLE_KOTLIN_LOCATION = Pattern.compile("^\\s*e:\\s*(?:file://)?(.+?):(\\d+):(\\d+)\\s*-\\s*(.*)$");
    private final Path workspaceRoot;

    public ProjectTool(Path workspaceRoot) {
        try {
            if (workspaceRoot == null || !Files.isDirectory(workspaceRoot)) {
                throw new IllegalArgumentException("Workspace must be an existing directory: " + workspaceRoot);
            }
            this.workspaceRoot = workspaceRoot.toRealPath();
        } catch (IOException ex) {
            throw new IllegalArgumentException("Cannot resolve workspace: " + workspaceRoot, ex);
        }
    }

    /**
     * 返回当前目录的项目类型、依据文件和已确认存在的常用脚本。
     *
     * <p>参数 {@code cwd} 可选，省略时检查工作区根目录；它始终会被限制在工作区内。
     */
    public ToolExecutionResult inspect(Map<String, Object> arguments) {
        try {
            Path directory = resolveDirectory(stringValue(arguments, "cwd"));
            ProjectInspection inspection = detect(directory);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("directory", workspaceRelative(directory));
            result.put("projectType", inspection.projectType());
            result.put("manifests", inspection.manifests());
            result.put("recommendedCommands", inspection.commands());
            result.put("guidance", inspection.guidance());
            return ToolExecutionResult.success(result);
        } catch (IllegalArgumentException ex) {
            return ToolExecutionResult.failure(ex.getMessage());
        }
    }

    /**
     * 在有限深度内发现多模块仓库中的项目根目录。
     *
     * <p>它跳过依赖和构建产物目录，既避免把 node_modules 误认为项目，也避免大型仓库的无界扫描。
     */
    public ToolExecutionResult discover(Map<String, Object> arguments) {
        try {
            Path root = resolveDirectory(stringValue(arguments, "cwd"));
            List<DiscoveredProject> discovered = discoverProjects(root);
            List<Map<String, Object>> projects = discovered.stream().map(this::projectSummary).toList();
            return ToolExecutionResult.success(Map.of(
                    "directory", workspaceRelative(root),
                    "projects", projects,
                    "truncated", projects.size() >= MAX_DISCOVERED_PROJECTS));
        } catch (IOException ex) {
            return ToolExecutionResult.failure("project.discover failed: " + ex.getMessage());
        } catch (IllegalArgumentException ex) {
            return ToolExecutionResult.failure(ex.getMessage());
        }
    }

    /**
     * 为已有代码仓库生成“先读哪里”的结构地图，而不是读取或上传全部源码。
     *
     * <p>地图只列出已经存在的约定目录和配置文件；模型仍需用 fs.read/fs.search 精读具体实现。
     */
    public ToolExecutionResult map(Map<String, Object> arguments) {
        try {
            Path root = resolveDirectory(stringValue(arguments, "cwd"));
            List<Map<String, Object>> modules = discoverProjects(root).stream().map(project -> {
                Map<String, Object> item = projectSummary(project);
                Path directory = project.directory();
                item.put("sourceRoots", existingDirectories(directory, "src/main/java", "src/main/kotlin", "src", "app", "pages"));
                item.put("testRoots", existingDirectories(directory, "src/test/java", "src/test/kotlin", "test", "tests", "__tests__"));
                item.put("configurationFiles", existing(directory,
                        "application.yml", "application.yaml", "application.properties", "package.json", "pom.xml", "build.gradle", "build.gradle.kts", ".env.example"));
                return item;
            }).toList();
            return ToolExecutionResult.success(Map.of(
                    "directory", workspaceRelative(root),
                    "modules", modules,
                    "guidance", "Inspect the relevant module manifest first, then search only its sourceRoots and testRoots before editing."));
        } catch (IOException ex) {
            return ToolExecutionResult.failure("project.map failed: " + ex.getMessage());
        } catch (IllegalArgumentException ex) {
            return ToolExecutionResult.failure(ex.getMessage());
        }
    }

    /**
     * 在受限的源码范围内建立轻量声明索引。
     *
     * <p>返回值只包含路径、行号、声明种类与声明行预览，不返回函数体或变量值。这样模型可以先精确
     * 定位，再用 {@code fs.read} 读取真正需要的少量代码，避免把整个仓库塞进上下文。
     */
    public ToolExecutionResult symbols(Map<String, Object> arguments) {
        try {
            Path root = resolveDirectory(stringValue(arguments, "cwd"));
            String query = normalizedQuery(stringValue(arguments, "query"));
            int maxResults = boundedInt(arguments, "maxResults", DEFAULT_MAX_SYMBOLS, 1, MAX_SYMBOLS);
            boolean includeTests = booleanValue(arguments, "includeTests", true);
            SymbolScan scan = new SymbolScan(root, query, maxResults, includeTests);
            Files.walkFileTree(root, java.util.Set.of(), MAX_SYMBOL_DEPTH, scan);
            List<Map<String, Object>> symbols = scan.symbols.stream()
                    .sorted(Comparator.comparing(SymbolDeclaration::path).thenComparingInt(SymbolDeclaration::line))
                    .map(SymbolDeclaration::toMap)
                    .toList();
            return ToolExecutionResult.success(Map.of(
                    "directory", workspaceRelative(root),
                    "query", query,
                    "symbols", symbols,
                    "scannedFiles", scan.scannedFiles,
                    "includeTests", includeTests,
                    "parser", "bounded-lightweight-declaration-index",
                    "truncated", scan.truncated,
                    "guidance", "Read the selected file and nearby lines before editing; this index is navigation evidence, not a compiler AST."));
        } catch (IOException ex) {
            return ToolExecutionResult.failure("project.symbols failed: " + ex.getMessage());
        } catch (IllegalArgumentException ex) {
            return ToolExecutionResult.failure(ex.getMessage());
        }
    }

    /**
     * 查找一个标识符在源码中的候选声明和候选引用，帮助模型在重命名、接口调整前先评估影响范围。
     *
     * <p>这不是语言服务器或完整 AST：注释、字符串和动态语言中的同名文本仍可能被列入。因此返回值显式使用
     * {@code candidate-reference} 分类，调用方必须用 {@code fs.read} 审阅具体上下文后才能修改代码。
     * 与 {@link #symbols(Map)} 一样，此方法只读取受限工作区中的常见源文件，并有文件数、深度、文件大小和结果数上限。
     */
    public ToolExecutionResult references(Map<String, Object> arguments) {
        try {
            Path root = resolveDirectory(stringValue(arguments, "cwd"));
            String symbol = requiredIdentifier(stringValue(arguments, "symbol"));
            int maxResults = boundedInt(arguments, "maxResults", DEFAULT_MAX_REFERENCES, 1, MAX_REFERENCES);
            boolean includeTests = booleanValue(arguments, "includeTests", true);
            ReferenceScan scan = new ReferenceScan(root, symbol, maxResults, includeTests);
            Files.walkFileTree(root, java.util.Set.of(), MAX_SYMBOL_DEPTH, scan);
            List<Map<String, Object>> references = scan.references.stream()
                    .sorted(Comparator.comparing(ReferenceOccurrence::path).thenComparingInt(ReferenceOccurrence::line))
                    .map(ReferenceOccurrence::toMap)
                    .toList();
            return ToolExecutionResult.success(Map.of(
                    "directory", workspaceRelative(root),
                    "symbol", symbol,
                    "references", references,
                    "scannedFiles", scan.scannedFiles,
                    "includeTests", includeTests,
                    "parser", "bounded-lexical-candidate-reference-index",
                    "truncated", scan.truncated,
                    "guidance", "Review each candidate with fs.read before editing. This is lexical navigation evidence, not a complete semantic reference graph."));
        } catch (IOException ex) {
            return ToolExecutionResult.failure("project.references failed: " + ex.getMessage());
        } catch (IllegalArgumentException ex) {
            return ToolExecutionResult.failure(ex.getMessage());
        }
    }

    /**
     * 将编译器、测试框架或构建工具输出归一化为可定位诊断。
     *
     * <p>诊断器不会重新执行失败命令，也不会因为日志中的路径去读取工作区之外的文件；它只是帮助
     * Agent 从“命令失败”收敛到下一次 {@code fs.read} 或补丁的具体位置。
     */
    public ToolExecutionResult diagnose(Map<String, Object> arguments) {
        String raw = stringValue(arguments, "output");
        if (raw == null || raw.isBlank()) {
            return ToolExecutionResult.failure("Missing required argument: output");
        }
        boolean truncated = raw.length() > MAX_DIAGNOSTIC_OUTPUT_CHARS;
        String output = truncated ? raw.substring(0, MAX_DIAGNOSTIC_OUTPUT_CHARS) : raw;
        List<Map<String, Object>> diagnostics = new ArrayList<>();
        int errors = 0;
        int warnings = 0;
        for (String line : output.split("\\R")) {
            if (diagnostics.size() >= MAX_DIAGNOSTICS) {
                truncated = true;
                break;
            }
            DiagnosticMatch match = parseDiagnostic(line);
            if (match == null) {
                continue;
            }
            if ("error".equals(match.severity)) errors++;
            if ("warning".equals(match.severity)) warnings++;
            diagnostics.add(Map.of(
                    "severity", match.severity,
                    "path", normalizeDiagnosticPath(match.path),
                    "line", match.line,
                    "column", match.column,
                    "message", boundedText(match.message, 1_000),
                    "source", match.source));
        }
        return ToolExecutionResult.success(Map.of(
                "diagnostics", diagnostics,
                "errorCount", errors,
                "warningCount", warnings,
                "inputTruncated", truncated,
                "guidance", diagnostics.isEmpty()
                        ? "No file locations matched the supported formats; inspect the command summary and rerun with a bounded output excerpt."
                        : "Read each reported path around its line before editing, then rerun the narrowest failing verification."));
    }

    private List<DiscoveredProject> discoverProjects(Path root) throws IOException {
        List<DiscoveredProject> projects = new ArrayList<>();
        Files.walkFileTree(root, java.util.Set.of(), MAX_DISCOVERY_DEPTH, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                if (!directory.equals(root) && ignoredDirectory(directory)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                ProjectInspection inspection = detect(directory);
                if (!"unknown".equals(inspection.projectType())) {
                    projects.add(new DiscoveredProject(directory, inspection));
                    return projects.size() >= MAX_DISCOVERED_PROJECTS ? FileVisitResult.TERMINATE : FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return projects;
    }

    private Map<String, Object> projectSummary(DiscoveredProject project) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("path", workspaceRelative(project.directory()));
        item.put("projectType", project.inspection().projectType());
        item.put("manifests", project.inspection().manifests());
        return item;
    }

    private ProjectInspection detect(Path directory) {
        // 检测顺序很重要：一个全栈仓库可能同时含 package.json 和 Gradle，
        // 这里优先报告根目录的 Java 构建文件，调用者可对前端子目录再执行一次 inspect。
        if (has(directory, "settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts")) {
            return gradleInspection(directory);
        }
        if (has(directory, "pom.xml")) {
            return mavenInspection(directory);
        }
        if (has(directory, "package.json")) {
            return nodeInspection(directory);
        }
        if (has(directory, "pyproject.toml", "requirements.txt", "setup.py")) {
            return pythonInspection(directory);
        }
        return new ProjectInspection(
                "unknown",
                List.of(),
                List.of(),
                "No supported project manifest was found here. Inspect a project subdirectory or create its manifest first.");
    }

    private ProjectInspection gradleInspection(Path directory) {
        List<String> manifests = existing(directory, "settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts");
        String gradle = wrapperCommand(directory, "gradlew", "gradlew.bat");
        List<Map<String, String>> commands = new ArrayList<>();
        commands.add(command("test", gradle + " test", "Compile and run the Gradle test suite."));
        commands.add(command("build", gradle + " build", "Build the project and run its verification tasks."));
        if (contains(directory, manifests, "org.springframework.boot")) {
            commands.add(command("start", gradle + " bootRun", "Start the detected Spring Boot application in the foreground."));
        }
        return new ProjectInspection("gradle", manifests, commands,
                "Run the test command first. Use the start command only after a successful build when live verification is needed.");
    }

    private ProjectInspection mavenInspection(Path directory) {
        List<String> manifests = List.of("pom.xml");
        String maven = wrapperCommand(directory, "mvnw", "mvnw.cmd");
        List<Map<String, String>> commands = new ArrayList<>();
        commands.add(command("test", maven + " test", "Compile and run the Maven test suite."));
        commands.add(command("package", maven + " package", "Create the application package after running tests."));
        if (contains(directory, manifests, "spring-boot")) {
            commands.add(command("start", maven + " spring-boot:run", "Start the detected Spring Boot application in the foreground."));
        }
        return new ProjectInspection("maven", manifests, commands,
                "Run the test command first. The start command is provided only when the manifest declares Spring Boot.");
    }

    private ProjectInspection nodeInspection(Path directory) {
        List<String> manifests = new ArrayList<>();
        manifests.add("package.json");
        manifests.addAll(existing(directory, "package-lock.json", "pnpm-lock.yaml", "yarn.lock", "bun.lockb"));
        String packageManager = packageManager(directory);
        String packageJson = readManifest(directory.resolve("package.json"));
        List<Map<String, String>> commands = new ArrayList<>();
        if (hasScript(packageJson, "test")) {
            commands.add(command("test", packageManager + " run test", "Run the package.json test script."));
        }
        if (hasScript(packageJson, "build")) {
            commands.add(command("build", packageManager + " run build", "Create the frontend or Node.js production build."));
        }
        if (hasScript(packageJson, "dev")) {
            commands.add(command("start", packageManager + " run dev", "Start the development server in the foreground."));
        } else if (hasScript(packageJson, "start")) {
            commands.add(command("start", packageManager + " run start", "Start the package.json start script in the foreground."));
        }
        return new ProjectInspection("node", manifests, commands,
                commands.isEmpty()
                        ? "package.json was found, but no test/build/dev/start scripts were detected. Inspect its scripts before running a command."
                        : "Only scripts actually declared in package.json are returned. Install dependencies only when the lockfile and project policy allow it.");
    }

    private ProjectInspection pythonInspection(Path directory) {
        List<String> manifests = existing(directory, "pyproject.toml", "requirements.txt", "setup.py");
        List<Map<String, String>> commands = new ArrayList<>();
        commands.add(command("test", "python -m pytest", "Run pytest when the project declares it as a dependency."));
        return new ProjectInspection("python", manifests, commands,
                "Python start commands are framework-specific. Inspect the manifest and source entry point before starting a process.");
    }

    private Path resolveDirectory(String requestedCwd) {
        Path candidate = requestedCwd == null || requestedCwd.isBlank()
                ? workspaceRoot
                : workspaceRoot.resolve(requestedCwd).normalize();
        if (!Files.isDirectory(candidate)) {
            throw new IllegalArgumentException("Project directory does not exist: " + candidate);
        }
        try {
            Path realPath = candidate.toRealPath();
            if (!realPath.startsWith(workspaceRoot)) {
                throw new IllegalArgumentException("Project directory must stay inside the configured workspace.");
            }
            return realPath;
        } catch (IOException ex) {
            throw new IllegalArgumentException("Cannot resolve project directory: " + candidate, ex);
        }
    }

    private String workspaceRelative(Path directory) {
        Path relative = workspaceRoot.relativize(directory);
        return relative.toString().isBlank() ? "." : relative.toString().replace('\\', '/');
    }

    private static boolean has(Path directory, String... names) {
        return !existing(directory, names).isEmpty();
    }

    private boolean ignoredDirectory(Path directory) {
        Path name = directory.getFileName();
        if (name == null) {
            return false;
        }
        // 与源码搜索使用相同的常见排除项，防止在生成目录与第三方依赖中浪费工具预算。
        return switch (name.toString()) {
            case ".git", ".gradle", "node_modules", "build", "target", "out", "dist", "coverage", ".venv" -> true;
            default -> false;
        };
    }

    private static List<String> existing(Path directory, String... names) {
        List<String> found = new ArrayList<>();
        for (String name : names) {
            if (Files.isRegularFile(directory.resolve(name))) {
                found.add(name);
            }
        }
        return found;
    }

    private List<String> existingDirectories(Path directory, String... names) {
        List<Path> found = new ArrayList<>();
        for (String name : names) {
            Path candidate = directory.resolve(name);
            if (Files.isDirectory(candidate)) {
                found.add(candidate);
            }
        }
        // 同时存在 src 与 src/main/java 时，后者才是更有价值的阅读起点。
        // 去掉父目录可以让模型少读无关文件，也让地图保持简洁。
        return found.stream()
                .filter(path -> found.stream().noneMatch(other -> !other.equals(path) && other.startsWith(path)))
                .map(this::workspaceRelative)
                .toList();
    }

    private static Map<String, String> command(String name, String value, String purpose) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("name", name);
        result.put("command", value);
        result.put("purpose", purpose);
        return result;
    }

    private static String wrapperCommand(Path directory, String unixWrapper, String windowsWrapper) {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        if (windows && Files.isRegularFile(directory.resolve(windowsWrapper))) {
            return windowsWrapper;
        }
        if (!windows && Files.isRegularFile(directory.resolve(unixWrapper))) {
            return "./" + unixWrapper;
        }
        return unixWrapper.startsWith("mvn") ? "mvn" : "gradle";
    }

    private static String packageManager(Path directory) {
        if (Files.isRegularFile(directory.resolve("pnpm-lock.yaml"))) {
            return "pnpm";
        }
        if (Files.isRegularFile(directory.resolve("yarn.lock"))) {
            return "yarn";
        }
        if (Files.isRegularFile(directory.resolve("bun.lockb"))) {
            return "bun";
        }
        return "npm";
    }

    private static boolean contains(Path directory, List<String> manifests, String text) {
        return manifests.stream().map(directory::resolve).map(ProjectTool::readManifest).anyMatch(content -> content.contains(text));
    }

    private static boolean hasScript(String packageJson, String script) {
        // 不需要把 package.json 的任意字段反序列化为业务对象；只需确认脚本键是否真实存在。
        return Pattern.compile("\\\"" + Pattern.quote(script) + "\\\"\\s*:").matcher(packageJson).find();
    }

    private static String readManifest(Path manifest) {
        try {
            if (!Files.isRegularFile(manifest) || Files.size(manifest) > MAX_MANIFEST_BYTES) {
                return "";
            }
            return Files.readString(manifest);
        } catch (IOException ex) {
            return "";
        }
    }

    private static String stringValue(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        return value == null ? null : value.toString();
    }

    private static String normalizedQuery(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String query = raw.trim();
        if (query.length() > MAX_SYMBOL_QUERY_CHARS || query.contains("\n") || query.contains("\r")) {
            throw new IllegalArgumentException("query must be a single line of at most " + MAX_SYMBOL_QUERY_CHARS + " characters.");
        }
        return query;
    }

    /**
     * 只接受语言中常见的简单标识符，避免把正则、路径或多行文本带入引用检索。
     * 真正的复杂成员表达式应先用 project.symbols 定位声明，再用 fs.read 审阅调用点。
     */
    private static String requiredIdentifier(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Missing required argument: symbol");
        }
        String symbol = raw.trim();
        if (symbol.length() > MAX_REFERENCE_IDENTIFIER_CHARS
                || !symbol.matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
            throw new IllegalArgumentException("symbol must be a simple identifier of at most "
                    + MAX_REFERENCE_IDENTIFIER_CHARS + " characters.");
        }
        return symbol;
    }

    private static int boundedInt(Map<String, Object> arguments, String key, int fallback, int minimum, int maximum) {
        String raw = stringValue(arguments, key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw);
            if (value < minimum || value > maximum) {
                throw new IllegalArgumentException(key + " must be between " + minimum + " and " + maximum + ".");
            }
            return value;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(key + " must be an integer.");
        }
    }

    private static boolean booleanValue(Map<String, Object> arguments, String key, boolean fallback) {
        String raw = stringValue(arguments, key);
        return raw == null || raw.isBlank() ? fallback : Boolean.parseBoolean(raw);
    }

    private final class SymbolScan extends SimpleFileVisitor<Path> {
        private final Path root;
        private final String queryLowerCase;
        private final int maxResults;
        private final boolean includeTests;
        private final List<SymbolDeclaration> symbols = new ArrayList<>();
        private int scannedFiles;
        private boolean truncated;

        private SymbolScan(Path root, String query, int maxResults, boolean includeTests) {
            this.root = root;
            this.queryLowerCase = query.toLowerCase(Locale.ROOT);
            this.maxResults = maxResults;
            this.includeTests = includeTests;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
            if (!directory.equals(root) && (ignoredDirectory(directory) || (!includeTests && testDirectory(directory)))) {
                return FileVisitResult.SKIP_SUBTREE;
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
            if (!attributes.isRegularFile() || !sourceLanguage(file).isPresent()) {
                return FileVisitResult.CONTINUE;
            }
            if (++scannedFiles > MAX_SYMBOL_FILES) {
                truncated = true;
                return FileVisitResult.TERMINATE;
            }
            try {
                if (Files.size(file) > MAX_SYMBOL_FILE_BYTES) {
                    return FileVisitResult.CONTINUE;
                }
                String language = sourceLanguage(file).orElseThrow();
                String[] lines = Files.readString(file).split("\\R", -1);
                for (int index = 0; index < lines.length; index++) {
                    SymbolMatch match = declaration(language, lines[index]);
                    if (match == null || (!queryLowerCase.isEmpty()
                            && !match.name.toLowerCase(Locale.ROOT).contains(queryLowerCase))) {
                        continue;
                    }
                    if (symbols.size() >= maxResults) {
                        truncated = true;
                        return FileVisitResult.TERMINATE;
                    }
                    symbols.add(new SymbolDeclaration(
                            workspaceRelative(file), index + 1, language, match.kind, match.name, declarationPreview(lines[index])));
                }
            } catch (IOException ignored) {
                // 单个源码文件被删除、锁定或不是 UTF-8 时跳过即可，不能中断整个代码导航流程。
            }
            return FileVisitResult.CONTINUE;
        }
    }

    /**
     * 逐行查找完整标识符。这里不用正则的 {@code \b}，因为 {@code $} 在多个语言中是合法标识符字符，
     * 而 \b 对它的边界判断不符合 JavaScript、TypeScript 和 Java 的实际语义。
     */
    private final class ReferenceScan extends SimpleFileVisitor<Path> {
        private final Path root;
        private final String symbol;
        private final Pattern occurrence;
        private final int maxResults;
        private final boolean includeTests;
        private final List<ReferenceOccurrence> references = new ArrayList<>();
        private int scannedFiles;
        private boolean truncated;

        private ReferenceScan(Path root, String symbol, int maxResults, boolean includeTests) {
            this.root = root;
            this.symbol = symbol;
            this.occurrence = Pattern.compile("(?<![A-Za-z0-9_$])" + Pattern.quote(symbol) + "(?![A-Za-z0-9_$])");
            this.maxResults = maxResults;
            this.includeTests = includeTests;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
            if (!directory.equals(root) && (ignoredDirectory(directory) || (!includeTests && testDirectory(directory)))) {
                return FileVisitResult.SKIP_SUBTREE;
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
            if (!attributes.isRegularFile() || sourceLanguage(file).isEmpty()) {
                return FileVisitResult.CONTINUE;
            }
            if (++scannedFiles > MAX_SYMBOL_FILES) {
                truncated = true;
                return FileVisitResult.TERMINATE;
            }
            try {
                if (Files.size(file) > MAX_SYMBOL_FILE_BYTES) {
                    return FileVisitResult.CONTINUE;
                }
                String language = sourceLanguage(file).orElseThrow();
                String[] lines = Files.readString(file).split("\\R", -1);
                for (int index = 0; index < lines.length; index++) {
                    Matcher matcher = occurrence.matcher(lines[index]);
                    if (!matcher.find()) {
                        continue;
                    }
                    int column = matcher.start() + 1;
                    if (references.size() >= maxResults) {
                        truncated = true;
                        return FileVisitResult.TERMINATE;
                    }
                    int occurrences = 1;
                    while (matcher.find()) {
                        occurrences++;
                    }
                    SymbolMatch declaration = declaration(language, lines[index]);
                    String kind = declaration != null && symbol.equals(declaration.name)
                            ? "declaration"
                            : "candidate-reference";
                    references.add(new ReferenceOccurrence(
                            workspaceRelative(file), index + 1, column, language, kind, occurrences,
                            declarationPreview(lines[index])));
                }
            } catch (IOException ignored) {
                // 单个源文件在扫描时被删除、锁定或无法以 UTF-8 读取时跳过即可，不能中断整个影响范围分析。
            }
            return FileVisitResult.CONTINUE;
        }
    }

    private static boolean testDirectory(Path directory) {
        Path name = directory.getFileName();
        if (name == null) return false;
        return switch (name.toString().toLowerCase(Locale.ROOT)) {
            case "test", "tests", "__tests__", "spec", "specs" -> true;
            default -> false;
        };
    }

    private static java.util.Optional<String> sourceLanguage(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".java")) return java.util.Optional.of("java");
        if (name.endsWith(".kt") || name.endsWith(".kts")) return java.util.Optional.of("kotlin");
        if (name.endsWith(".ts") || name.endsWith(".tsx")) return java.util.Optional.of("typescript");
        if (name.endsWith(".js") || name.endsWith(".jsx") || name.endsWith(".mjs") || name.endsWith(".cjs")) return java.util.Optional.of("javascript");
        if (name.endsWith(".py")) return java.util.Optional.of("python");
        if (name.endsWith(".go")) return java.util.Optional.of("go");
        if (name.endsWith(".rs")) return java.util.Optional.of("rust");
        return java.util.Optional.empty();
    }

    private static SymbolMatch declaration(String language, String line) {
        return switch (language) {
            case "java" -> javaDeclaration(line);
            case "kotlin" -> firstMatch(line, KOTLIN_DECLARATION);
            case "typescript", "javascript" -> firstMatch(line, WEB_DECLARATION, WEB_ARROW);
            case "python" -> firstMatch(line, PYTHON_DECLARATION);
            case "go" -> firstMatch(line, GO_DECLARATION);
            case "rust" -> firstMatch(line, RUST_DECLARATION);
            default -> null;
        };
    }

    private static SymbolMatch javaDeclaration(String line) {
        SymbolMatch type = firstMatch(line, JAVA_TYPE);
        if (type != null) {
            return type;
        }
        var method = JAVA_METHOD.matcher(line);
        return method.find() ? new SymbolMatch("method", method.group(1)) : null;
    }

    private static SymbolMatch firstMatch(String line, Pattern... patterns) {
        for (Pattern pattern : patterns) {
            var matcher = pattern.matcher(line);
            if (matcher.find()) {
                if (pattern == WEB_ARROW) {
                    return new SymbolMatch("function", matcher.group(1));
                }
                return new SymbolMatch(matcher.group(1), matcher.group(2));
            }
        }
        return null;
    }

    private static String declarationPreview(String line) {
        String preview = line.trim().replaceAll("\\s+", " ");
        return preview.length() <= 300 ? preview : preview.substring(0, 300) + "...";
    }

    private DiagnosticMatch parseDiagnostic(String line) {
        Matcher matcher = MAVEN_LOCATION.matcher(line);
        if (matcher.matches()) {
            return diagnostic(matcher.group(1), matcher.group(2), matcher.group(3), matcher.group(4), "error", "maven");
        }
        matcher = TYPESCRIPT_LOCATION.matcher(line);
        if (matcher.matches()) {
            return diagnostic(matcher.group(1), matcher.group(2), matcher.group(3), matcher.group(5),
                    matcher.group(4).toLowerCase(Locale.ROOT), "typescript");
        }
        matcher = GRADLE_KOTLIN_LOCATION.matcher(line);
        if (matcher.matches()) {
            return diagnostic(matcher.group(1), matcher.group(2), matcher.group(3), matcher.group(4), "error", "gradle");
        }
        matcher = LOCATION_WITH_COLUMNS.matcher(line);
        if (matcher.matches()) {
            String message = matcher.group(4);
            String severity = message.toLowerCase(Locale.ROOT).contains("warning") ? "warning" : "error";
            // 一些测试框架只给出“文件:行号”而没有列号。此时保留诊断，
            // 用 0 明确表示“列号未知”，避免模型误以为该问题无法定位。
            return diagnostic(matcher.group(1), matcher.group(2),
                    matcher.group(3) == null ? "0" : matcher.group(3), message, severity, "compiler-or-test");
        }
        return null;
    }

    private static DiagnosticMatch diagnostic(String path, String line, String column, String message,
            String severity, String source) {
        try {
            return new DiagnosticMatch(path.trim(), Integer.parseInt(line), Integer.parseInt(column), message.trim(), severity, source);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String normalizeDiagnosticPath(String rawPath) {
        String path = rawPath.replace("file://", "").trim().replace('\\', '/');
        try {
            Path candidate = Path.of(path);
            if (candidate.isAbsolute()) {
                Path real = candidate.normalize();
                if (real.startsWith(workspaceRoot)) {
                    return workspaceRoot.relativize(real).toString().replace('\\', '/');
                }
                return path;
            }
        } catch (Exception ignored) {
            // 编译器可能输出带驱动器或 URI 的非标准路径，保留已截断的原文供人工判断。
        }
        return path;
    }

    private static String boundedText(String text, int maxLength) {
        String value = text == null ? "" : text.trim().replaceAll("\\s+", " ");
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }

    private record SymbolMatch(String kind, String name) {
    }

    private record SymbolDeclaration(String path, int line, String language, String kind, String name, String declaration) {
        private Map<String, Object> toMap() {
            return Map.of(
                    "path", path,
                    "line", line,
                    "language", language,
                    "kind", kind,
                    "name", name,
                    "declaration", declaration);
        }
    }

    private record ReferenceOccurrence(
            String path, int line, int column, String language, String kind, int occurrences, String preview) {
        private Map<String, Object> toMap() {
            return Map.of(
                    "path", path,
                    "line", line,
                    "column", column,
                    "language", language,
                    "kind", kind,
                    "occurrences", occurrences,
                    "preview", preview);
        }
    }

    private record DiagnosticMatch(String path, int line, int column, String message, String severity, String source) {
    }

    private record ProjectInspection(
            String projectType,
            List<String> manifests,
            List<Map<String, String>> commands,
            String guidance) {
    }

    /** 内部记录发现到的模块位置和其清单解析结果，避免 discover 与 map 重复扫描逻辑。 */
    private record DiscoveredProject(Path directory, ProjectInspection inspection) {
    }
}
