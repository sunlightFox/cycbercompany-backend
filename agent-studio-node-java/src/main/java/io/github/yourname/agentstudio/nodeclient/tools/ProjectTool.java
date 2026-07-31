package io.github.yourname.agentstudio.nodeclient.tools;

import io.github.yourname.agentstudio.nodeclient.runtime.ToolExecutionResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 在指定工作区中识别常见项目，并给出可供后续工具执行的建议命令。
 *
 * <p>它<strong>不会</strong>执行构建、安装依赖或启动服务。这样编码代理先获得可靠的项目事实，
 * 再由需要审批的 {@code shell.run}/{@code process.start} 执行真正有副作用的操作。
 */
public final class ProjectTool {

    private static final int MAX_MANIFEST_BYTES = 128 * 1024;
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

    private static List<String> existing(Path directory, String... names) {
        List<String> found = new ArrayList<>();
        for (String name : names) {
            if (Files.isRegularFile(directory.resolve(name))) {
                found.add(name);
            }
        }
        return found;
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

    private record ProjectInspection(
            String projectType,
            List<String> manifests,
            List<Map<String, String>> commands,
            String guidance) {
    }
}
