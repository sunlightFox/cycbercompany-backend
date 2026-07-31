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
}
