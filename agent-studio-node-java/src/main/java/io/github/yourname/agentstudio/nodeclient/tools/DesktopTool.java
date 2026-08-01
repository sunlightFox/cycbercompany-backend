package io.github.yourname.agentstudio.nodeclient.tools;

import io.github.yourname.agentstudio.nodeclient.runtime.ToolExecutionResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Windows 桌面相关命令的最小执行器。
 *
 * <p>本类不决定风险、审批或用户是否有权更换壁纸。这些判断由服务端完成；
 * 节点只在收到已批准的 {@code system.desktop.set_wallpaper} 命令后调用本机系统 API。
 */
public class DesktopTool {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(".jpg", ".jpeg", ".png", ".bmp");
    private static final int MAX_OUTPUT_CHARS = 2_000;

    private final CommandExecutor commandExecutor;
    private final String osName;

    public DesktopTool() {
        this(new PowerShellCommandExecutor(), System.getProperty("os.name", ""));
    }

    DesktopTool(CommandExecutor commandExecutor, String osName) {
        this.commandExecutor = commandExecutor;
        this.osName = osName == null ? "" : osName;
    }

    /**
     * 将当前 Windows 用户的桌面壁纸设置为给定图片。
     *
     * <p>路径不写死在客户端中，服务端传入并经审批保存。这里使用 PowerShell 调用
     * {@code SystemParametersInfoW}，比仅修改注册表更可靠，因为它会立即通知桌面外壳刷新。
     */
    public ToolExecutionResult setWallpaper(Map<String, Object> arguments) {
        if (!osName.toLowerCase(Locale.ROOT).contains("windows")) {
            return ToolExecutionResult.failure("system.desktop.set_wallpaper is supported only on Windows nodes.");
        }
        String requested = stringValue(arguments, "path");
        if (requested == null || requested.isBlank()) {
            return ToolExecutionResult.failure("Missing required argument: path");
        }
        try {
            Path image = Path.of(requested).toAbsolutePath().normalize();
            if (!Files.isRegularFile(image)) {
                return ToolExecutionResult.failure("Wallpaper image does not exist or is not a regular file: " + image);
            }
            if (!isSupportedImage(image)) {
                return ToolExecutionResult.failure("Wallpaper image must be a JPG, JPEG, PNG, or BMP file.");
            }

            CommandResult execution = commandExecutor.execute(powerShellCommand(image));
            if (execution.exitCode() != 0) {
                return ToolExecutionResult.failure("Windows rejected the wallpaper update: " + preview(execution.output()));
            }
            return ToolExecutionResult.success(Map.of(
                    "path", image.toString(),
                    "applied", true));
        } catch (Exception ex) {
            return ToolExecutionResult.failure("Unable to set desktop wallpaper: " + message(ex));
        }
    }

    private static List<String> powerShellCommand(Path image) {
        // 图片路径先编码后再嵌入脚本，避免空格、单引号等文件名字符被解释为 PowerShell 命令。
        String encodedPath = Base64.getEncoder().encodeToString(image.toString().getBytes(StandardCharsets.UTF_8));
        String script = """
                $encodedPath = '%s'
                $wallpaper = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($encodedPath))
                $source = @'
                using System;
                using System.Runtime.InteropServices;
                public static class AgentStudioWallpaper {
                    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
                    public static extern bool SystemParametersInfo(int action, int parameter, string value, int flags);
                }
                '@
                Add-Type -TypeDefinition $source
                if (-not [AgentStudioWallpaper]::SystemParametersInfo(20, 0, $wallpaper, 3)) {
                    throw "SystemParametersInfoW failed with Win32 error $([Runtime.InteropServices.Marshal]::GetLastWin32Error())"
                }
                """.formatted(encodedPath);
        String encodedScript = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        return List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", encodedScript);
    }

    private static boolean isSupportedImage(Path image) {
        String name = image.getFileName().toString().toLowerCase(Locale.ROOT);
        return SUPPORTED_EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    private static String stringValue(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        return value == null ? null : value.toString();
    }

    private static String preview(String output) {
        String normalized = output == null ? "" : output.trim();
        return normalized.length() <= MAX_OUTPUT_CHARS ? normalized : normalized.substring(0, MAX_OUTPUT_CHARS) + "...";
    }

    private static String message(Exception ex) {
        return ex.getMessage() == null || ex.getMessage().isBlank()
                ? ex.getClass().getSimpleName()
                : ex.getMessage();
    }

    interface CommandExecutor {
        CommandResult execute(List<String> command) throws IOException, InterruptedException;
    }

    record CommandResult(int exitCode, String output) {
    }

    private static final class PowerShellCommandExecutor implements CommandExecutor {
        @Override
        public CommandResult execute(List<String> command) throws IOException, InterruptedException {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return new CommandResult(process.waitFor(), output);
        }
    }
}
