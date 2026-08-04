package io.github.yourname.agentstudio.nodeclient.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 验证项目识别只依据工作区内的清单文件，且不会把目录边界扩大到工作区外。 */
class ProjectToolTest {

    @Test
    void recognizesDeclaredNodeScriptsAndTheLockfilePackageManager() throws Exception {
        Path workspace = Files.createTempDirectory("agent-studio-project");
        Path frontend = Files.createDirectories(workspace.resolve("frontend"));
        Files.writeString(frontend.resolve("package.json"), """
                {"scripts":{"test":"vitest run","build":"vite build","dev":"vite"}}
                """);
        Files.writeString(frontend.resolve("pnpm-lock.yaml"), "lockfileVersion: '9.0'\n");

        var result = new ProjectTool(workspace).inspect(Map.of("cwd", "frontend"));

        assertTrue(result.success());
        assertEquals("node", result.result().get("projectType"));
        assertEquals("frontend", result.result().get("directory"));
        assertEquals(List.of("package.json", "pnpm-lock.yaml"), result.result().get("manifests"));
        @SuppressWarnings("unchecked")
        List<Map<String, String>> commands = (List<Map<String, String>>) result.result().get("recommendedCommands");
        assertEquals(List.of("test", "build", "start"), commands.stream().map(command -> command.get("name")).toList());
        assertTrue(commands.stream().allMatch(command -> command.get("command").startsWith("pnpm run ")));
    }

    @Test
    void recognizesSpringBootGradleAndRejectsDirectoriesOutsideTheWorkspace() throws Exception {
        Path workspace = Files.createTempDirectory("agent-studio-project");
        Files.writeString(workspace.resolve("build.gradle.kts"), "plugins { id(\"org.springframework.boot\") }");

        ProjectTool tool = new ProjectTool(workspace);
        var result = tool.inspect(Map.of());
        var outside = tool.inspect(Map.of("cwd", ".."));

        assertTrue(result.success());
        assertEquals("gradle", result.result().get("projectType"));
        @SuppressWarnings("unchecked")
        List<Map<String, String>> commands = (List<Map<String, String>>) result.result().get("recommendedCommands");
        assertTrue(commands.stream().anyMatch(command -> "start".equals(command.get("name"))));
        assertFalse(outside.success());
        assertTrue(outside.errorMessage().contains("configured workspace"));
    }

    @Test
    void discoversSeparatedFrontendAndBackendWithoutEnteringDependencyDirectories() throws Exception {
        Path workspace = Files.createTempDirectory("agent-studio-project");
        Path backend = Files.createDirectories(workspace.resolve("services/backend"));
        Path frontend = Files.createDirectories(workspace.resolve("apps/frontend"));
        Files.writeString(backend.resolve("pom.xml"), "<project/>\n");
        Files.writeString(frontend.resolve("package.json"), "{\"scripts\":{\"build\":\"vite build\"}}\n");
        Path ignored = Files.createDirectories(workspace.resolve("apps/frontend/node_modules/nested"));
        Files.writeString(ignored.resolve("package.json"), "{\"scripts\":{}}\n");

        var result = new ProjectTool(workspace).discover(Map.of());

        assertTrue(result.success());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> projects = (List<Map<String, Object>>) result.result().get("projects");
        assertEquals(List.of("apps/frontend", "services/backend"), projects.stream().map(project -> project.get("path")).sorted().toList());
        assertFalse(projects.stream().anyMatch(project -> project.get("path").toString().contains("node_modules")));
    }

    @Test
    void mapsExistingSourceTestAndConfigurationLocationsForAnUnfamiliarRepository() throws Exception {
        Path workspace = Files.createTempDirectory("agent-studio-project");
        Path backend = Files.createDirectories(workspace.resolve("backend/src/main/java"));
        Files.createDirectories(workspace.resolve("backend/src/test/java"));
        Files.writeString(workspace.resolve("backend/pom.xml"), "<project/>\n");
        Files.writeString(workspace.resolve("backend/application.yml"), "server:\n  port: 8080\n");

        var result = new ProjectTool(workspace).map(Map.of());

        assertTrue(result.success());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> modules = (List<Map<String, Object>>) result.result().get("modules");
        assertEquals(1, modules.size());
        assertEquals("backend", modules.getFirst().get("path"));
        assertEquals(List.of("backend/src/main/java"), modules.getFirst().get("sourceRoots"));
        assertEquals(List.of("backend/src/test/java"), modules.getFirst().get("testRoots"));
        assertTrue(((List<?>) modules.getFirst().get("configurationFiles")).contains("pom.xml"));
        assertTrue(backend.toString().contains("backend"));
    }

    @Test
    void indexesBoundedDeclarationsAcrossBackendFrontendAndTests() throws Exception {
        Path workspace = Files.createTempDirectory("agent-studio-project");
        Path java = Files.createDirectories(workspace.resolve("backend/src/main/java/demo"));
        Path tests = Files.createDirectories(workspace.resolve("backend/src/test/java/demo"));
        Path web = Files.createDirectories(workspace.resolve("frontend/src"));
        Files.writeString(java.resolve("TaskService.java"), """
                package demo;
                public class TaskService {
                    public String createTask(String title) { return title; }
                }
                """);
        Files.writeString(tests.resolve("TaskServiceTest.java"), """
                class TaskServiceTest {
                    void createsTask() { }
                }
                """);
        Files.writeString(web.resolve("TaskList.tsx"), """
                export interface Task { id: string }
                export const TaskList = () => null;
                """);

        ProjectTool tool = new ProjectTool(workspace);
        var all = tool.symbols(Map.of("query", "task", "maxResults", 20));
        var productionOnly = tool.symbols(Map.of("query", "task", "includeTests", false));

        assertTrue(all.success());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> allSymbols = (List<Map<String, Object>>) all.result().get("symbols");
        assertTrue(allSymbols.stream().anyMatch(symbol -> "TaskService".equals(symbol.get("name"))));
        assertTrue(allSymbols.stream().anyMatch(symbol -> "createTask".equals(symbol.get("name"))));
        assertTrue(allSymbols.stream().anyMatch(symbol -> "TaskList".equals(symbol.get("name"))));
        assertTrue(allSymbols.stream().allMatch(symbol -> ((Number) symbol.get("line")).intValue() > 0));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> productionSymbols = (List<Map<String, Object>>) productionOnly.result().get("symbols");
        assertFalse(productionSymbols.stream().anyMatch(symbol -> symbol.get("path").toString().contains("src/test")));
    }

    @Test
    void findsBoundedCandidateReferencesWithoutMatchingLongerIdentifiersOrDependencies() throws Exception {
        Path workspace = Files.createTempDirectory("agent-studio-references");
        Path production = Files.createDirectories(workspace.resolve("backend/src/main/java/demo"));
        Path tests = Files.createDirectories(workspace.resolve("backend/src/test/java/demo"));
        Path dependencies = Files.createDirectories(workspace.resolve("frontend/node_modules/example"));
        Files.writeString(production.resolve("TaskService.java"), "public class TaskService {}\n");
        Files.writeString(production.resolve("TaskConsumer.java"), "var task = new TaskService(); TaskService.class; TaskServiceExtra ignored;\n");
        Files.writeString(tests.resolve("TaskServiceTest.java"), "new TaskService();\n");
        Files.writeString(dependencies.resolve("ignored.js"), "const value = TaskService;\n");

        ProjectTool tool = new ProjectTool(workspace);
        var all = tool.references(Map.of("symbol", "TaskService", "maxResults", 20));
        var productionOnly = tool.references(Map.of("symbol", "TaskService", "includeTests", false));
        var invalid = tool.references(Map.of("symbol", "TaskService()"));

        assertTrue(all.success());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> allReferences = (List<Map<String, Object>>) all.result().get("references");
        assertEquals(3, allReferences.size());
        assertTrue(allReferences.stream().anyMatch(reference -> "declaration".equals(reference.get("kind"))));
        assertTrue(allReferences.stream().anyMatch(reference -> "candidate-reference".equals(reference.get("kind"))
                && Integer.valueOf(2).equals(reference.get("occurrences"))));
        assertFalse(allReferences.stream().anyMatch(reference -> reference.get("path").toString().contains("node_modules")));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> productionReferences = (List<Map<String, Object>>) productionOnly.result().get("references");
        assertEquals(2, productionReferences.size());
        assertFalse(productionReferences.stream().anyMatch(reference -> reference.get("path").toString().contains("src/test")));
        assertFalse(invalid.success());
        assertTrue(invalid.errorMessage().contains("simple identifier"));
    }

    @Test
    void usesJavaAstForMultilineDeclarationsAndIgnoresCommentsAndStringLiterals() throws Exception {
        Path workspace = Files.createTempDirectory("agent-studio-java-ast");
        Path source = Files.createDirectories(workspace.resolve("src/main/java/demo"));
        Files.writeString(source.resolve("TaskService.java"), """
                package demo;
                public class TaskService {
                    // TaskService in a comment is not a source reference.
                    private final String label = "TaskService in a string is not a source reference";

                    public
                    String createTask(
                            String title) {
                        return new TaskService().toString() + title;
                    }
                }
                """);

        ProjectTool tool = new ProjectTool(workspace);
        var symbols = tool.symbols(Map.of("query", "createTask"));
        var references = tool.references(Map.of("symbol", "TaskService"));

        assertTrue(symbols.success());
        assertEquals("bounded-java-ast-and-lexical-declaration-index", symbols.result().get("parser"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> declarations = (List<Map<String, Object>>) symbols.result().get("symbols");
        assertTrue(declarations.stream().anyMatch(symbol -> "createTask".equals(symbol.get("name"))));

        assertTrue(references.success());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> occurrences = (List<Map<String, Object>>) references.result().get("references");
        assertTrue(occurrences.stream().anyMatch(reference -> "declaration".equals(reference.get("kind"))));
        assertTrue(occurrences.stream().anyMatch(reference -> "candidate-reference".equals(reference.get("kind"))));
        assertFalse(occurrences.stream().anyMatch(reference -> reference.get("preview").toString().contains("comment")));
        assertFalse(occurrences.stream().anyMatch(reference -> reference.get("preview").toString().contains("string is not")));
    }

    @Test
    void normalizesMavenTypeScriptGradleAndCompilerDiagnosticsWithoutExecutingAnything() throws Exception {
        Path workspace = Files.createTempDirectory("agent-studio-diagnostics");
        Path java = Files.createDirectories(workspace.resolve("backend/src/main/java/demo")).resolve("TaskService.java");
        Path web = Files.createDirectories(workspace.resolve("frontend/src")).resolve("TaskList.tsx");
        Path kotlin = Files.createDirectories(workspace.resolve("backend/src/main/kotlin/demo")).resolve("TaskApi.kt");
        Files.writeString(java, "class TaskService {}\n");
        Files.writeString(web, "export const TaskList = () => null;\n");
        Files.writeString(kotlin, "class TaskApi\n");

        String output = """
                [ERROR] %s:[12,8] cannot find symbol
                frontend/src/TaskList.tsx(5,17): error TS2304: Cannot find name 'missingTask'.
                e: file://%s:7:3 - Unresolved reference: task
                backend/src/main/java/demo/TaskService.java:21: assertion failed
                """.formatted(java, kotlin);

        var result = new ProjectTool(workspace).diagnose(Map.of("output", output));

        assertTrue(result.success());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> diagnostics = (List<Map<String, Object>>) result.result().get("diagnostics");
        assertEquals(4, diagnostics.size());
        assertEquals("backend/src/main/java/demo/TaskService.java", diagnostics.getFirst().get("path"));
        assertEquals("maven", diagnostics.getFirst().get("source"));
        assertEquals("typescript", diagnostics.get(1).get("source"));
        assertEquals("backend/src/main/kotlin/demo/TaskApi.kt", diagnostics.get(2).get("path"));
        assertEquals("gradle", diagnostics.get(2).get("source"));
        assertEquals(0, diagnostics.get(3).get("column"));
        assertEquals("compiler-or-test", diagnostics.get(3).get("source"));
    }

    @Test
    void boundsLargeDiagnosticStreamsAndPreservesTheFirstActionableLocations() throws Exception {
        Path workspace = Files.createTempDirectory("agent-studio-diagnostics");
        StringBuilder output = new StringBuilder();
        for (int index = 1; index <= 125; index++) {
            output.append("src/Example.ts(").append(index).append(",1): error TS1000: broken\n");
        }

        var result = new ProjectTool(workspace).diagnose(Map.of("output", output.toString()));

        assertTrue(result.success());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> diagnostics = (List<Map<String, Object>>) result.result().get("diagnostics");
        assertEquals(120, diagnostics.size());
        assertEquals(120, result.result().get("errorCount"));
        assertEquals(true, result.result().get("inputTruncated"));
        assertEquals("src/Example.ts", diagnostics.getFirst().get("path"));
    }

    @Test
    void omitsDiagnosticLocationsOutsideTheConfiguredWorkspace() throws Exception {
        Path workspace = Files.createTempDirectory("agent-studio-diagnostics");
        Path outside = Files.createTempFile("agent-studio-private", ".java");

        var result = new ProjectTool(workspace).diagnose(Map.of(
                "output", outside + ":12:3: cannot access private source"));

        assertTrue(result.success());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> diagnostics = (List<Map<String, Object>>) result.result().get("diagnostics");
        assertEquals("[outside-workspace path omitted]", diagnostics.getFirst().get("path"));
        assertFalse(result.result().toString().contains(outside.toString()));
    }
}
