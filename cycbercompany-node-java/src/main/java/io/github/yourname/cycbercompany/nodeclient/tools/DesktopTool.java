package io.github.yourname.cycbercompany.nodeclient.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.yourname.cycbercompany.nodeclient.runtime.ToolExecutionResult;
import java.awt.AWTException;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.IOException;
import javax.imageio.ImageIO;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Windows 桌面相关命令的最小执行器。
 *
 * <p>本类不决定风险、审批或用户是否有权更换壁纸。这些判断由服务端完成；
 * 节点只在收到已批准的 {@code system.desktop.set_wallpaper} 命令后调用本机系统 API。
 */
public class DesktopTool {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(".jpg", ".jpeg", ".png", ".bmp");
    private static final int MAX_OUTPUT_CHARS = 2_000;
    private static final int MAX_WINDOWS = 100;
    private static final int MAX_UI_CONTROLS = 250;
    private static final int MAX_METADATA_TEXT_CHARS = 240;
    private static final int MAX_UI_VALUE_CHARS = 2_000;
    private static final int DEFAULT_UI_WAIT_MS = 5_000;
    private static final int MAX_UI_WAIT_MS = 30_000;
    private static final int UI_WAIT_POLL_MS = 250;
    private static final ObjectMapper JSON = new ObjectMapper();
    /**
     * 只允许启动少量 Windows 内置、无网络副作用的交互应用。
     *
     * <p>这里绝不能接受调用方传入的 exe 路径、工作目录或参数。需要任意命令的场景必须
     * 走已有的 {@code system.shell.run}，并由审批页面展示完整命令。
     */
    private static final Map<String, String> APPROVED_APPLICATIONS = Map.of(
            "notepad", "notepad.exe",
            "paint", "mspaint.exe",
            "calculator", "calc.exe");

    private final CommandExecutor commandExecutor;
    private final String osName;
    /**
     * 仅保存等待上传的临时截图。WebSocket 发送结果前会由 ArtifactUploadClient 校验、上传并删除文件，
     * 因此此处绝不把截图的绝对路径放入工具结果。
     */
    private final Path artifactRoot;
    private final ScreenCapture screenCapture;
    /**
     * UI Automation 元数据会随着窗口重绘而失效。每次快照都生成单调递增版本，并仅保留
     * 最新一份可用于动作的控件引用，避免模型用过期的文字条件重新猜测目标控件。
     */
    private final AtomicLong uiSnapshotSequence = new AtomicLong();
    private volatile UiSnapshotState latestUiSnapshot = UiSnapshotState.empty();
    /**
     * 窗口激活和键盘输入同样属于有副作用操作，不能只相信模型给出的任意 PID。
     * 这里单独保存最近一份“顶层窗口”快照，避免与 UI 控件快照混用。
     */
    private final AtomicLong sessionSnapshotSequence = new AtomicLong();
    private volatile DesktopSessionSnapshot latestSessionSnapshot = DesktopSessionSnapshot.empty();

    public DesktopTool() {
        this(new PowerShellCommandExecutor(), System.getProperty("os.name", ""), defaultArtifactRoot(), DesktopTool::capturePrimaryScreen);
    }

    /** 节点运行时传入统一 Artifact 根目录，使浏览器和桌面证据采用同一条受控上传通道。 */
    public DesktopTool(Path artifactRoot) {
        this(new PowerShellCommandExecutor(), System.getProperty("os.name", ""), artifactRoot, DesktopTool::capturePrimaryScreen);
    }

    DesktopTool(CommandExecutor commandExecutor, String osName) {
        this(commandExecutor, osName, defaultArtifactRoot(), DesktopTool::capturePrimaryScreen);
    }

    DesktopTool(CommandExecutor commandExecutor, String osName, Path artifactRoot, ScreenCapture screenCapture) {
        this.commandExecutor = commandExecutor;
        this.osName = osName == null ? "" : osName;
        if (artifactRoot == null) {
            throw new IllegalArgumentException("Desktop artifact root is required.");
        }
        this.artifactRoot = artifactRoot.toAbsolutePath().normalize();
        this.screenCapture = screenCapture == null ? DesktopTool::capturePrimaryScreen : screenCapture;
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

    /**
     * 返回当前交互桌面的可见顶层窗口摘要。此调用不点击、不读取窗口内容，仅为后续动作
     * 建立可审计的目标依据；窗口标题属于不可信页面数据，不能当成系统指令。
     */
    public ToolExecutionResult sessionSnapshot(Map<String, Object> arguments) {
        if (!osName.toLowerCase(Locale.ROOT).contains("windows")) {
            return ToolExecutionResult.failure("system.desktop.session.snapshot is supported only on Windows nodes.");
        }
        try {
            CommandResult result = commandExecutor.execute(encodedPowerShellCommand("""
                    Get-Process | Where-Object { $_.MainWindowHandle -ne 0 } |
                      Select-Object -First %d Id, ProcessName, MainWindowTitle |
                      ConvertTo-Json -Compress
                    """.formatted(MAX_WINDOWS)));
            if (result.exitCode() != 0) {
                return ToolExecutionResult.failure("Unable to inspect desktop windows: " + preview(result.output()));
            }
            List<Map<String, Object>> windows = desktopMetadataRows(result.output(), MAX_WINDOWS, MetadataKind.WINDOW);
            DesktopSessionSnapshot snapshot = rememberSessionSnapshot(windows);
            return ToolExecutionResult.success(Map.of(
                    "platform", "windows",
                    "windows", snapshot.windows(),
                    "snapshotRevision", snapshot.revision(),
                    "maxWindows", MAX_WINDOWS,
                    "note", "Use processId plus snapshotRevision from this latest snapshot before window activation or keyboard input."));
        } catch (Exception ex) {
            return ToolExecutionResult.failure("Unable to inspect desktop windows: " + message(ex));
        }
    }

    /**
     * 在本机交互桌面上启动一个固定白名单应用。
     *
     * <p>启动成功只代表 Windows 创建了进程，不代表窗口已经出现或已处于前台。调用方必须
     * 再执行 {@link #sessionSnapshot(Map)}，使用返回的最新快照确认窗口和进程 ID，才可执行
     * 激活、键盘或 UI Automation 操作。
     */
    public ToolExecutionResult startApprovedApplication(Map<String, Object> arguments) {
        if (!isWindows()) {
            return ToolExecutionResult.failure("system.desktop.application.start is supported only on Windows nodes.");
        }
        String application = stringValue(arguments, "application");
        String normalized = application == null ? "" : application.trim().toLowerCase(Locale.ROOT);
        String executable = APPROVED_APPLICATIONS.get(normalized);
        if (executable == null) {
            return ToolExecutionResult.failure("Unsupported desktop application. Use one of: "
                    + String.join(", ", APPROVED_APPLICATIONS.keySet()) + ".");
        }
        try {
            // executable 来自不可变白名单，不拼接模型输入，避免此入口退化为任意命令执行。
            CommandResult result = commandExecutor.execute(encodedPowerShellCommand("""
                    $process = Start-Process -FilePath '%s' -PassThru
                    [pscustomobject]@{ Id = $process.Id; ProcessName = $process.ProcessName } | ConvertTo-Json -Compress
                    """.formatted(executable)));
            if (result.exitCode() != 0) {
                return ToolExecutionResult.failure("Unable to start approved desktop application: " + preview(result.output()));
            }
            Map<String, Object> process = JSON.readValue(result.output(), new TypeReference<LinkedHashMap<String, Object>>() { });
            String rawProcessId = stringValue(process, "Id");
            Long processId = rawProcessId != null && rawProcessId.matches("[1-9][0-9]*")
                    ? Long.parseLong(rawProcessId)
                    : null;
            if (processId == null || processId <= 0) {
                return ToolExecutionResult.failure("Windows did not return a valid process ID for the approved application.");
            }
            return ToolExecutionResult.success(Map.of(
                    "application", normalized,
                    "processId", processId,
                    "processName", preview(stringValue(process, "ProcessName")),
                    "nextStep", "Call system.desktop.session.snapshot and confirm this process before interacting."));
        } catch (Exception ex) {
            return ToolExecutionResult.failure("Unable to start approved desktop application: " + message(ex));
        }
    }

    /** 激活经过快照确认的进程主窗口；具体控件操作仍应由后续 UI Automation sidecar 提供。 */
    /**
     * 捕获当前交互用户可见的主显示器，作为 Windows 桌面操作的人工复核证据。
     *
     * <p>截图本身可能包含敏感信息，所以该工具在服务端始终属于高风险、逐次审批操作。
     * 它不支持坐标或区域参数，防止调用者借由精确裁剪绕开“整个可见桌面”的审批语义；
     * 图片只会落入节点 Artifact 根目录，并由既有上传器转换为不可变服务端引用。
     */
    public ToolExecutionResult screenshot(Map<String, Object> arguments) {
        if (!isWindows()) {
            return ToolExecutionResult.failure("system.desktop.screenshot is supported only on Windows nodes.");
        }
        try {
            Path output = createScreenshotPath();
            ScreenCaptureResult captured = screenCapture.capture(output);
            if (!Files.isRegularFile(output)) {
                return ToolExecutionResult.failure("Desktop screenshot capture did not create an artifact file.");
            }
            return ToolExecutionResult.success(Map.of(
                    "platform", "windows",
                    "capture", "primary-display",
                    "width", captured.width(),
                    "height", captured.height(),
                    "mimeType", "image/png",
                    "artifactType", "desktop-screenshot",
                    "artifactPath", relativeArtifactPath(output)));
        } catch (Exception ex) {
            return ToolExecutionResult.failure("Unable to capture the visible Windows desktop: " + message(ex));
        }
    }

    public ToolExecutionResult activateWindow(Map<String, Object> arguments) {
        if (!osName.toLowerCase(Locale.ROOT).contains("windows")) {
            return ToolExecutionResult.failure("system.desktop.window.activate is supported only on Windows nodes.");
        }
        try {
            String processId = Long.toString(requireLatestSessionProcess(arguments));
            CommandResult result = commandExecutor.execute(encodedPowerShellCommand("""
                    $shell = New-Object -ComObject WScript.Shell
                    if (-not $shell.AppActivate(%s)) { throw 'No interactive main window for process %s' }
                    """.formatted(processId, processId)));
            if (result.exitCode() != 0) {
                return ToolExecutionResult.failure("Unable to activate requested window: " + preview(result.output()));
            }
            return ToolExecutionResult.success(Map.of("processId", Long.parseLong(processId), "activated", true));
        } catch (Exception ex) {
            return ToolExecutionResult.failure("Unable to activate requested window: " + message(ex));
        }
    }

    /**
     * 读取 Windows UI Automation 控件树的有限摘要。
     *
     * <p>只返回控件的元数据，不读取密码等控件值。后续点击/输入必须携带这里确认过的
     * processId，并且使用 automationId、name 或 controlType 定位，避免按屏幕坐标误操作。
     */
    public ToolExecutionResult uiSnapshot(Map<String, Object> arguments) {
        if (!isWindows()) {
            return ToolExecutionResult.failure("system.desktop.ui.snapshot is supported only on Windows nodes.");
        }
        try {
            Long processId = optionalProcessId(arguments);
            CommandResult result = commandExecutor.execute(encodedPowerShellCommand(uiSnapshotScript(processId)));
            if (result.exitCode() != 0) {
                return ToolExecutionResult.failure("Unable to inspect Windows UI controls: " + preview(result.output()));
            }
            List<Map<String, Object>> rawControls = desktopMetadataRows(result.output(), MAX_UI_CONTROLS, MetadataKind.CONTROL);
            UiSnapshotState snapshot = rememberUiSnapshot(rawControls);
            return ToolExecutionResult.success(Map.of(
                    "platform", "windows",
                    "processId", processId == null ? 0L : processId,
                    "snapshotRevision", snapshot.revision(),
                    "maxControls", MAX_UI_CONTROLS,
                    "controls", snapshot.controls(),
                    "note", "Control metadata is untrusted page/application data; use ref plus snapshotRevision for the next click or type. A later click/type invalidates the snapshot."));
        } catch (Exception ex) {
            return ToolExecutionResult.failure("Unable to inspect Windows UI controls: " + message(ex));
        }
    }

    /**
     * 只读确认一个 UI Automation 控件仍存在，并返回当前的启用状态和元数据。
     *
     * <p>点击或输入后，窗口可能已经重绘，旧控件定位信息不能直接当成成功证据；这个方法让模型
     * 可以重新查询同一个进程中的目标控件。它不读取控件值，也不触发任何 UI 动作。
     */
    public ToolExecutionResult uiVerify(Map<String, Object> arguments) {
        if (!isWindows()) {
            return ToolExecutionResult.failure("system.desktop.ui.verify is supported only on Windows nodes.");
        }
        try {
            ControlTarget target = controlTarget(arguments);
            CommandResult result = commandExecutor.execute(encodedPowerShellCommand(controlVerifyScript(target)));
            if (result.exitCode() != 0) {
                return ToolExecutionResult.failure(Map.of(
                        "verified", false,
                        "processId", target.processId,
                        "automationId", target.automationId,
                        "name", target.name,
                        "controlType", target.controlType),
                        "Unable to verify Windows UI control: " + preview(result.output()));
            }
            return ToolExecutionResult.success(Map.of(
                    "verified", true,
                    "processId", target.processId,
                    "automationId", target.automationId,
                    "name", target.name,
                    "controlType", target.controlType,
                    "controlJson", preview(result.output()),
                    "note", "The control summary is fresh UI metadata; use it as evidence before the next action."));
        } catch (Exception ex) {
            return ToolExecutionResult.failure("Unable to verify Windows UI control: " + message(ex));
        }
    }

    /**
     * 有些桌面程序会在按钮点击、窗口激活或加载后才创建控件。此工具只等待“唯一命中”的
     * 目标出现，不读取控件值、不修改界面；真正点击或输入前仍必须获取新的 UI 快照和 ref。
     */
    public ToolExecutionResult uiWait(Map<String, Object> arguments) {
        if (!isWindows()) {
            return ToolExecutionResult.failure("system.desktop.ui.wait is supported only on Windows nodes.");
        }
        try {
            ControlTarget target = controlTarget(arguments);
            int timeoutMs = boundedMilliseconds(arguments, "timeoutMs", DEFAULT_UI_WAIT_MS, 100, MAX_UI_WAIT_MS);
            long startedAt = System.nanoTime();
            int attempts = 0;
            while (true) {
                attempts++;
                CommandResult result = commandExecutor.execute(encodedPowerShellCommand(controlVerifyScript(target)));
                long waitedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
                if (result.exitCode() == 0) {
                    return ToolExecutionResult.success(Map.of(
                            "available", true,
                            "attempts", attempts,
                            "waitedMs", waitedMs,
                            "processId", target.processId,
                            "automationId", target.automationId,
                            "name", target.name,
                            "controlType", target.controlType,
                            "note", "The control is available now. Call system.desktop.ui.snapshot again before click or type."));
                }
                if (waitedMs >= timeoutMs) {
                    return ToolExecutionResult.failure(Map.of(
                                    "available", false,
                                    "attempts", attempts,
                                    "waitedMs", waitedMs,
                                    "processId", target.processId,
                                    "automationId", target.automationId,
                                    "name", target.name,
                                    "controlType", target.controlType),
                            "Windows UI control did not become uniquely available before the timeout.");
                }
                try {
                    Thread.sleep(Math.min(UI_WAIT_POLL_MS, Math.max(1, timeoutMs - waitedMs)));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return ToolExecutionResult.failure("Windows UI wait was interrupted.");
                }
            }
        } catch (Exception ex) {
            return ToolExecutionResult.failure("Unable to wait for Windows UI control: " + message(ex));
        }
    }

    /**
     * 读取一个非密码 UI Automation ValuePattern 控件的当前值，用于确认已审批的输入确实生效。
     *
     * <p>这不是通用桌面内容抓取能力：没有 ValuePattern 的控件会失败；Windows 标记为密码的
     * 控件会在节点侧直接拒绝；输出最多保留 {@value MAX_UI_VALUE_CHARS} 个字符。因为普通文本
     * 仍可能属于私密数据，这个工具在服务端目录中保持高风险并要求逐次审批。
     */
    public ToolExecutionResult uiReadValue(Map<String, Object> arguments) {
        if (!isWindows()) {
            return ToolExecutionResult.failure("system.desktop.ui.read_value is supported only on Windows nodes.");
        }
        try {
            ControlTarget target = controlTarget(arguments);
            CommandResult result = commandExecutor.execute(encodedPowerShellCommand(controlReadValueScript(target)));
            if (result.exitCode() != 0) {
                return ToolExecutionResult.failure(Map.of(
                        "read", false,
                        "processId", target.processId,
                        "automationId", target.automationId,
                        "name", target.name,
                        "controlType", target.controlType),
                        "Unable to read Windows UI control value: " + preview(result.output()));
            }
            Map<String, Object> value = JSON.readValue(result.output(), new TypeReference<>() { });
            Object rawValue = value.get("value");
            if (!(rawValue instanceof String text)) {
                return ToolExecutionResult.failure("Windows UI value reader returned an invalid value result.");
            }
            boolean truncated = Boolean.TRUE.equals(value.get("truncated"));
            return ToolExecutionResult.success(Map.of(
                    "read", true,
                    "processId", target.processId,
                    "automationId", target.automationId,
                    "name", target.name,
                    "controlType", target.controlType,
                    "value", text,
                    "truncated", truncated));
        } catch (Exception ex) {
            return ToolExecutionResult.failure("Unable to read Windows UI control value: " + message(ex));
        }
    }

    /** 点击 UI Automation 控件，不依赖容易漂移的屏幕坐标。 */
    public ToolExecutionResult uiClick(Map<String, Object> arguments) {
        if (!isWindows()) {
            return ToolExecutionResult.failure("system.desktop.ui.click is supported only on Windows nodes.");
        }
        try {
            ControlTarget target = snapshotControlTarget(arguments);
            // 一旦请求了可能产生副作用的动作，旧快照不能再证明当前 UI。即使 PowerShell
            // 返回失败，也可能已触发部分应用行为，因此在真正执行前就使引用失效。
            invalidateUiSnapshot();
            CommandResult result = commandExecutor.execute(encodedPowerShellCommand(controlActionScript(target, "click", null)));
            if (result.exitCode() != 0) {
                return ToolExecutionResult.failure("Unable to click Windows UI control: " + preview(result.output()));
            }
            return ToolExecutionResult.success(Map.of(
                    "clicked", true,
                    "processId", target.processId,
                    "automationId", target.automationId,
                    "name", target.name,
                    "controlType", target.controlType));
        } catch (Exception ex) {
            return ToolExecutionResult.failure("Unable to click Windows UI control: " + message(ex));
        }
    }

    /** 给 UI Automation 的 ValuePattern 输入文本；不会读取原有控件值。 */
    public ToolExecutionResult uiType(Map<String, Object> arguments) {
        if (!isWindows()) {
            return ToolExecutionResult.failure("system.desktop.ui.type is supported only on Windows nodes.");
        }
        String text = stringValue(arguments, "text");
        if (text == null) {
            return ToolExecutionResult.failure("Missing required argument: text");
        }
        if (text.length() > 32_000) {
            return ToolExecutionResult.failure("text is too long; maximum is 32000 characters.");
        }
        try {
            ControlTarget target = snapshotControlTarget(arguments);
            // 输入失败并不等于目标应用完全没有接收到字符；保守地要求重新快照。
            invalidateUiSnapshot();
            CommandResult result = commandExecutor.execute(encodedPowerShellCommand(controlActionScript(target, "type", text)));
            if (result.exitCode() != 0) {
                return ToolExecutionResult.failure("Unable to type into Windows UI control: " + preview(result.output()));
            }
            return ToolExecutionResult.success(Map.of(
                    "typed", true,
                    "textLength", text.length(),
                    "processId", target.processId,
                    "automationId", target.automationId,
                    "name", target.name,
                    "controlType", target.controlType));
        } catch (Exception ex) {
            return ToolExecutionResult.failure("Unable to type into Windows UI control: " + message(ex));
        }
    }

    /** 激活已确认的窗口后发送一个 Windows SendKeys 序列，例如 {ENTER} 或 ^A。 */
    public ToolExecutionResult keyboardPress(Map<String, Object> arguments) {
        if (!isWindows()) {
            return ToolExecutionResult.failure("system.desktop.keyboard.press is supported only on Windows nodes.");
        }
        String keys = stringValue(arguments, "keys");
        if (keys == null || keys.isBlank()) {
            return ToolExecutionResult.failure("Missing required argument: keys");
        }
        if (keys.length() > 200) {
            return ToolExecutionResult.failure("keys is too long; maximum is 200 characters.");
        }
        try {
            String processId = Long.toString(requireLatestSessionProcess(arguments));
            String script = """
                    function Decode([string]$value) { [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($value)) }
                    Add-Type -AssemblyName System.Windows.Forms
                    $shell = New-Object -ComObject WScript.Shell
                    if (-not $shell.AppActivate(%s)) { throw 'No interactive main window for process %s' }
                    [System.Windows.Forms.SendKeys]::SendWait((Decode '%s'))
                    """.formatted(processId, processId, encodedValue(keys));
            CommandResult result = commandExecutor.execute(encodedPowerShellCommand(script));
            if (result.exitCode() != 0) {
                return ToolExecutionResult.failure("Unable to send Windows keyboard input: " + preview(result.output()));
            }
            return ToolExecutionResult.success(Map.of("sent", true, "processId", Long.parseLong(processId), "keysLength", keys.length()));
        } catch (Exception ex) {
            return ToolExecutionResult.failure("Unable to send Windows keyboard input: " + message(ex));
        }
    }

    /** 读取桌面剪贴板的 bounded 文本摘要，不返回图片或二进制内容。 */
    public ToolExecutionResult clipboardGet(Map<String, Object> arguments) {
        if (!isWindows()) {
            return ToolExecutionResult.failure("system.desktop.clipboard.get is supported only on Windows nodes.");
        }
        try {
            String script = """
                    function Decode([string]$value) { [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($value)) }
                    Add-Type -AssemblyName System.Windows.Forms
                    if ([System.Windows.Forms.Clipboard]::ContainsText()) {
                        [System.Windows.Forms.Clipboard]::GetText()
                    }
                    """;
            CommandResult result = commandExecutor.execute(encodedPowerShellCommand(script));
            if (result.exitCode() != 0) {
                return ToolExecutionResult.failure("Unable to read Windows clipboard: " + preview(result.output()));
            }
            String text = result.output() == null ? "" : result.output();
            return ToolExecutionResult.success(Map.of(
                    "hasText", !text.isBlank(),
                    "text", preview(text),
                    "truncated", text.trim().length() > MAX_OUTPUT_CHARS));
        } catch (Exception ex) {
            return ToolExecutionResult.failure("Unable to read Windows clipboard: " + message(ex));
        }
    }

    /** 将明确提供的文本写入剪贴板；写入动作保持高风险并交由服务端审批。 */
    public ToolExecutionResult clipboardSet(Map<String, Object> arguments) {
        if (!isWindows()) {
            return ToolExecutionResult.failure("system.desktop.clipboard.set is supported only on Windows nodes.");
        }
        String text = stringValue(arguments, "text");
        if (text == null) {
            return ToolExecutionResult.failure("Missing required argument: text");
        }
        if (text.length() > 32_000) {
            return ToolExecutionResult.failure("text is too long; maximum is 32000 characters.");
        }
        try {
            String script = """
                    function Decode([string]$value) { [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($value)) }
                    Add-Type -AssemblyName System.Windows.Forms
                    [System.Windows.Forms.Clipboard]::SetText((Decode '%s'))
                    """.formatted(encodedValue(text));
            CommandResult result = commandExecutor.execute(encodedPowerShellCommand(script));
            if (result.exitCode() != 0) {
                return ToolExecutionResult.failure("Unable to write Windows clipboard: " + preview(result.output()));
            }
            return ToolExecutionResult.success(Map.of("written", true, "textLength", text.length()));
        } catch (Exception ex) {
            return ToolExecutionResult.failure("Unable to write Windows clipboard: " + message(ex));
        }
    }

    private boolean isWindows() {
        return osName.toLowerCase(Locale.ROOT).contains("windows");
    }

    private static Long optionalProcessId(Map<String, Object> arguments) {
        Object value = arguments == null ? null : arguments.get("processId");
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        return parseProcessId(value.toString());
    }

    private static Long requiredProcessId(Map<String, Object> arguments) {
        Object value = arguments == null ? null : arguments.get("processId");
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("Missing required argument: processId");
        }
        return parseProcessId(value.toString());
    }

    private static Long parseProcessId(String value) {
        if (value == null || !value.matches("[1-9][0-9]*")) {
            throw new IllegalArgumentException("processId must be a positive integer returned by desktop snapshot.");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("processId is outside the supported range.");
        }
    }

    /**
     * 将窗口副作用绑定到最新 session.snapshot：既校验快照版本，也校验该 PID 确实在快照中。
     * PowerShell 仍会通过 AppActivate 二次确认窗口可交互，防止快照之后窗口已关闭的情况。
     */
    private long requireLatestSessionProcess(Map<String, Object> arguments) {
        long processId = requiredProcessId(arguments);
        long revision = requiredSessionSnapshotRevision(arguments);
        DesktopSessionSnapshot snapshot = latestSessionSnapshot;
        if (snapshot.revision() != revision) {
            throw new IllegalArgumentException(
                    "Desktop session snapshot revision is stale. Call system.desktop.session.snapshot again before acting.");
        }
        if (!snapshot.processIds().contains(processId)) {
            throw new IllegalArgumentException(
                    "processId is not present in the latest desktop session snapshot. Inspect the desktop again before acting.");
        }
        return processId;
    }

    private static long requiredSessionSnapshotRevision(Map<String, Object> arguments) {
        String value = stringValue(arguments, "snapshotRevision");
        if (value == null || !value.matches("[1-9][0-9]*")) {
            throw new IllegalArgumentException(
                    "snapshotRevision must be a positive integer returned by system.desktop.session.snapshot.");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("snapshotRevision is outside the supported range.");
        }
    }

    private static int boundedMilliseconds(
            Map<String, Object> arguments,
            String key,
            int fallback,
            int minimum,
            int maximum) {
        Object raw = arguments == null ? null : arguments.get(key);
        if (raw == null || raw.toString().isBlank()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.toString());
            if (value < minimum || value > maximum) {
                throw new IllegalArgumentException(key + " must be between " + minimum + " and " + maximum + ".");
            }
            return value;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(key + " must be an integer.");
        }
    }

    private static ControlTarget controlTarget(Map<String, Object> arguments) {
        Long processId = requiredProcessId(arguments);
        String automationId = stringValue(arguments, "automationId");
        String name = stringValue(arguments, "name");
        String controlType = stringValue(arguments, "controlType");
        if ((automationId == null || automationId.isBlank())
                && (name == null || name.isBlank())
                && (controlType == null || controlType.isBlank())) {
            throw new IllegalArgumentException("Provide automationId, name, or controlType from desktop.ui.snapshot.");
        }
        return new ControlTarget(processId, nullToEmpty(automationId), nullToEmpty(name), nullToEmpty(controlType));
    }

    /**
     * 解析 UI 快照中产生的不可猜测引用，并拒绝上一版快照或不一致的附带元数据。
     *
     * <p>Windows UI Automation 没有跨进程稳定的元素句柄可以安全传给模型，因此 ref 只是
     * 节点内存中最新快照的受控索引；真正执行前 PowerShell 仍会再次查找并要求唯一命中。
     */
    private ControlTarget snapshotControlTarget(Map<String, Object> arguments) {
        String ref = stringValue(arguments, "ref");
        if (ref == null || ref.isBlank()) {
            throw new IllegalArgumentException("ref from system.desktop.ui.snapshot is required for click or type.");
        }
        long revision = requiredUiSnapshotRevision(arguments);
        UiSnapshotState snapshot = latestUiSnapshot;
        if (snapshot.revision() != revision) {
            throw new IllegalArgumentException(
                    "Desktop UI snapshot revision is stale. Call system.desktop.ui.snapshot again before acting.");
        }
        ControlTarget target = snapshot.refs().get(ref);
        if (target == null) {
            throw new IllegalArgumentException("Unknown desktop UI ref for the latest snapshot. Call system.desktop.ui.snapshot again.");
        }
        assertConsistentSnapshotSelector(arguments, "processId", Long.toString(target.processId()));
        assertConsistentSnapshotSelector(arguments, "automationId", target.automationId());
        assertConsistentSnapshotSelector(arguments, "name", target.name());
        assertConsistentSnapshotSelector(arguments, "controlType", target.controlType());
        return target;
    }

    private static long requiredUiSnapshotRevision(Map<String, Object> arguments) {
        String key = "snapshotRevision";
        String value = stringValue(arguments, key);
        if (value == null || !value.matches("[1-9][0-9]*")) {
            throw new IllegalArgumentException(key + " must be a positive integer returned by system.desktop.ui.snapshot.");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(key + " is outside the supported range.");
        }
    }

    private static void assertConsistentSnapshotSelector(Map<String, Object> arguments, String key, String expected) {
        String supplied = stringValue(arguments, key);
        if (supplied != null && !supplied.isBlank() && !supplied.equals(expected)) {
            throw new IllegalArgumentException(key + " does not match the referenced desktop UI snapshot control.");
        }
    }

    private UiSnapshotState rememberUiSnapshot(List<Map<String, Object>> rawControls) {
        long revision = uiSnapshotSequence.incrementAndGet();
        Map<String, ControlTarget> refs = new LinkedHashMap<>();
        List<Map<String, Object>> controls = new ArrayList<>();
        int ordinal = 0;
        for (Map<String, Object> raw : rawControls) {
            LinkedHashMap<String, Object> row = new LinkedHashMap<>(raw);
            // 顶层 window 只是树的上下文，不能作为 descendant 控件点击。没有稳定定位字段的
            // 行也可以展示给模型，但不会获得可执行 ref。
            if ("control".equals(row.get("kind")) && row.get("processId") instanceof Number processId) {
                String automationId = stringValue(row, "automationId");
                String name = stringValue(row, "name");
                String controlType = stringValue(row, "controlType");
                if (hasSelector(automationId, name, controlType)) {
                    String ref = "ui-" + revision + "-" + (++ordinal);
                    refs.put(ref, new ControlTarget(
                            processId.longValue(),
                            nullToEmpty(automationId),
                            nullToEmpty(name),
                            nullToEmpty(controlType)));
                    row.put("ref", ref);
                }
            }
            controls.add(Map.copyOf(row));
        }
        UiSnapshotState snapshot = new UiSnapshotState(revision, refs, controls);
        latestUiSnapshot = snapshot;
        return snapshot;
    }

    /** 从结构化窗口元数据中提取可用于下一次系统操作的 PID 白名单。 */
    private DesktopSessionSnapshot rememberSessionSnapshot(List<Map<String, Object>> rawWindows) {
        long revision = sessionSnapshotSequence.incrementAndGet();
        java.util.LinkedHashSet<Long> processIds = new java.util.LinkedHashSet<>();
        List<Map<String, Object>> windows = new ArrayList<>();
        for (Map<String, Object> rawWindow : rawWindows) {
            windows.add(Map.copyOf(rawWindow));
            Object rawProcessId = rawWindow.get("processId");
            if (rawProcessId instanceof Number processId && processId.longValue() > 0) {
                processIds.add(processId.longValue());
            }
        }
        DesktopSessionSnapshot snapshot = new DesktopSessionSnapshot(revision, processIds, windows);
        latestSessionSnapshot = snapshot;
        return snapshot;
    }

    private void invalidateUiSnapshot() {
        latestUiSnapshot = UiSnapshotState.empty();
    }

    private static boolean hasSelector(String automationId, String name, String controlType) {
        return (automationId != null && !automationId.isBlank())
                || (name != null && !name.isBlank())
                || (controlType != null && !controlType.isBlank());
    }

    private static String uiSnapshotScript(Long processId) {
        String filter = processId == null ? "$targetProcessId = 0" : "$targetProcessId = %d".formatted(processId);
        return """
                function Decode([string]$value) { [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($value)) }
                Add-Type -AssemblyName UIAutomationClient
                Add-Type -AssemblyName UIAutomationTypes
                %s
                $root = [System.Windows.Automation.AutomationElement]::RootElement
                $trueCondition = [System.Windows.Automation.Condition]::TrueCondition
                $items = New-Object System.Collections.Generic.List[object]
                $windows = $root.FindAll([System.Windows.Automation.TreeScope]::Children, $trueCondition)
                foreach ($window in $windows) {
                    if ($targetProcessId -ne 0 -and $window.Current.ProcessId -ne $targetProcessId) { continue }
                    $items.Add([pscustomobject]@{
                        kind = 'window'; processId = $window.Current.ProcessId; name = $window.Current.Name
                        automationId = $window.Current.AutomationId; controlType = $window.Current.ControlType.ProgrammaticName
                        className = $window.Current.ClassName; enabled = $window.Current.IsEnabled
                    })
                    $controls = $window.FindAll([System.Windows.Automation.TreeScope]::Descendants, $trueCondition)
                    foreach ($control in $controls) {
                        if ($items.Count -ge %d) { break }
                        $items.Add([pscustomobject]@{
                            kind = 'control'; processId = $control.Current.ProcessId; name = $control.Current.Name
                            automationId = $control.Current.AutomationId; controlType = $control.Current.ControlType.ProgrammaticName
                            className = $control.Current.ClassName; enabled = $control.Current.IsEnabled
                        })
                    }
                    if ($items.Count -ge %d) { break }
                }
                $items | ConvertTo-Json -Compress -Depth 5
                """.formatted(filter, MAX_UI_CONTROLS, MAX_UI_CONTROLS);
    }

    private static String controlActionScript(ControlTarget target, String action, String text) {
        String value = text == null ? "" : encodedValue(text);
        return """
                function Decode([string]$value) { [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($value)) }
                Add-Type -AssemblyName UIAutomationClient
                Add-Type -AssemblyName UIAutomationTypes
                $targetProcessId = %d
                $automationId = Decode '%s'
                $name = Decode '%s'
                $controlType = Decode '%s'
                $root = [System.Windows.Automation.AutomationElement]::RootElement
                $trueCondition = [System.Windows.Automation.Condition]::TrueCondition
                $windows = $root.FindAll([System.Windows.Automation.TreeScope]::Children, $trueCondition)
                $window = $windows | Where-Object { $_.Current.ProcessId -eq $targetProcessId } | Select-Object -First 1
                if ($null -eq $window) { throw "No top-level window found for process $targetProcessId" }
                $controls = $window.FindAll([System.Windows.Automation.TreeScope]::Descendants, $trueCondition)
                $matches = @($controls | Where-Object {
                    ($automationId -eq '' -or $_.Current.AutomationId -eq $automationId) -and
                    ($name -eq '' -or $_.Current.Name -eq $name) -and
                    ($controlType -eq '' -or $_.Current.ControlType.ProgrammaticName -like "*$controlType*")
                })
                if ($matches.Count -ne 1) { throw "Expected exactly one matching UI Automation control, found $($matches.Count)" }
                $control = $matches[0]
                if ('click' -eq '%s') {
                    try { $control.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern).Invoke() }
                    catch { $control.GetCurrentPattern([System.Windows.Automation.SelectionItemPattern]::Pattern).Select() }
                } else {
                    $control.GetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern).SetValue((Decode '%s'))
                }
                """.formatted(target.processId, encodedValue(target.automationId), encodedValue(target.name),
                encodedValue(target.controlType), action, value);
    }

    private static String controlVerifyScript(ControlTarget target) {
        return """
                function Decode([string]$value) { [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($value)) }
                Add-Type -AssemblyName UIAutomationClient
                Add-Type -AssemblyName UIAutomationTypes
                $targetProcessId = %d
                $automationId = Decode '%s'
                $name = Decode '%s'
                $controlType = Decode '%s'
                $root = [System.Windows.Automation.AutomationElement]::RootElement
                $trueCondition = [System.Windows.Automation.Condition]::TrueCondition
                $windows = $root.FindAll([System.Windows.Automation.TreeScope]::Children, $trueCondition)
                $window = $windows | Where-Object { $_.Current.ProcessId -eq $targetProcessId } | Select-Object -First 1
                if ($null -eq $window) { throw "No top-level window found for process $targetProcessId" }
                $controls = $window.FindAll([System.Windows.Automation.TreeScope]::Descendants, $trueCondition)
                $matches = @($controls | Where-Object {
                    ($automationId -eq '' -or $_.Current.AutomationId -eq $automationId) -and
                    ($name -eq '' -or $_.Current.Name -eq $name) -and
                    ($controlType -eq '' -or $_.Current.ControlType.ProgrammaticName -like "*$controlType*")
                })
                if ($matches.Count -ne 1) { throw "Expected exactly one matching UI Automation control, found $($matches.Count)" }
                $control = $matches[0]
                [pscustomobject]@{
                    exists = $true; processId = $control.Current.ProcessId; name = $control.Current.Name
                    automationId = $control.Current.AutomationId; controlType = $control.Current.ControlType.ProgrammaticName
                    enabled = $control.Current.IsEnabled
                } | ConvertTo-Json -Compress
                """.formatted(target.processId, encodedValue(target.automationId), encodedValue(target.name),
                encodedValue(target.controlType));
    }

    /** PowerShell 端再次检查 IsPassword，确保服务端策略之外还有节点本地的最后一道保护。 */
    private static String controlReadValueScript(ControlTarget target) {
        return """
                function Decode([string]$value) { [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($value)) }
                Add-Type -AssemblyName UIAutomationClient
                Add-Type -AssemblyName UIAutomationTypes
                $targetProcessId = %d
                $automationId = Decode '%s'
                $name = Decode '%s'
                $controlType = Decode '%s'
                $root = [System.Windows.Automation.AutomationElement]::RootElement
                $trueCondition = [System.Windows.Automation.Condition]::TrueCondition
                $windows = $root.FindAll([System.Windows.Automation.TreeScope]::Children, $trueCondition)
                $window = $windows | Where-Object { $_.Current.ProcessId -eq $targetProcessId } | Select-Object -First 1
                if ($null -eq $window) { throw "No top-level window found for process $targetProcessId" }
                $controls = $window.FindAll([System.Windows.Automation.TreeScope]::Descendants, $trueCondition)
                $matches = @($controls | Where-Object {
                    ($automationId -eq '' -or $_.Current.AutomationId -eq $automationId) -and
                    ($name -eq '' -or $_.Current.Name -eq $name) -and
                    ($controlType -eq '' -or $_.Current.ControlType.ProgrammaticName -like "*$controlType*")
                })
                if ($matches.Count -ne 1) { throw "Expected exactly one matching UI Automation control, found $($matches.Count)" }
                $control = $matches[0]
                if ($control.Current.IsPassword) { throw 'Password controls cannot be read' }
                $pattern = [System.Windows.Automation.ValuePattern]$control.GetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern)
                $text = $pattern.Current.Value
                $truncated = $text.Length -gt %d
                if ($truncated) { $text = $text.Substring(0, %d) }
                [pscustomobject]@{
                    value = $text; truncated = $truncated; processId = $control.Current.ProcessId
                    automationId = $control.Current.AutomationId; name = $control.Current.Name
                    controlType = $control.Current.ControlType.ProgrammaticName
                } | ConvertTo-Json -Compress -Depth 5
                """.formatted(target.processId, encodedValue(target.automationId), encodedValue(target.name),
                encodedValue(target.controlType), MAX_UI_VALUE_CHARS, MAX_UI_VALUE_CHARS);
    }

    private static String encodedValue(String value) {
        return Base64.getEncoder().encodeToString((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record ControlTarget(Long processId, String automationId, String name, String controlType) {
    }

    /**
     * 最近一次顶层窗口快照的最小内存索引。
     *
     * <p>它只保存版本号、已展示给调用方的 PID 集合与已净化的窗口摘要，不保存窗口句柄、
     * 原始 PowerShell 输出或可被伪造的任意绝对路径。
     */
    private record DesktopSessionSnapshot(
            long revision,
            Set<Long> processIds,
            List<Map<String, Object>> windows) {

        private DesktopSessionSnapshot {
            processIds = Set.copyOf(processIds == null ? Set.of() : processIds);
            windows = List.copyOf(windows == null ? List.of() : windows);
        }

        static DesktopSessionSnapshot empty() {
            return new DesktopSessionSnapshot(0, Set.of(), List.of());
        }
    }

    /** Latest bounded UI snapshot retained only in node memory; it is never sent back as an opaque handle. */
    private record UiSnapshotState(
            long revision,
            Map<String, ControlTarget> refs,
            List<Map<String, Object>> controls) {

        private UiSnapshotState {
            refs = Map.copyOf(refs == null ? Map.of() : refs);
            controls = List.copyOf(controls == null ? List.of() : controls);
        }

        static UiSnapshotState empty() {
            return new UiSnapshotState(0, Map.of(), List.of());
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
                public static class CycberCompanyWallpaper {
                    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
                    public static extern bool SystemParametersInfo(int action, int parameter, string value, int flags);
                }
                '@
                Add-Type -TypeDefinition $source
                if (-not [CycberCompanyWallpaper]::SystemParametersInfo(20, 0, $wallpaper, 3)) {
                    throw "SystemParametersInfoW failed with Win32 error $([Runtime.InteropServices.Marshal]::GetLastWin32Error())"
                }
                """.formatted(encodedPath);
        return encodedPowerShellCommand(script);
    }

    private static List<String> encodedPowerShellCommand(String script) {
        String encodedScript = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        return List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", encodedScript);
    }

    /** 用 Java AWT 直接截取当前交互桌面，不依赖外部脚本，也不会读入任何窗口的文本内容。 */
    private static ScreenCaptureResult capturePrimaryScreen(Path output) throws IOException, AWTException {
        if (GraphicsEnvironment.isHeadless()) {
            throw new IOException("Desktop screenshot is unavailable in a headless Windows session.");
        }
        java.awt.Dimension size = Toolkit.getDefaultToolkit().getScreenSize();
        if (size.width <= 0 || size.height <= 0) {
            throw new IOException("Windows did not report a usable primary display size.");
        }
        BufferedImage image = new Robot().createScreenCapture(new Rectangle(0, 0, size.width, size.height));
        Files.createDirectories(output.getParent());
        if (!ImageIO.write(image, "png", output.toFile())) {
            throw new IOException("PNG image writer is unavailable.");
        }
        return new ScreenCaptureResult(image.getWidth(), image.getHeight());
    }

    /** 随机文件名避免并发 Run 之间互相覆盖，也避免模型控制产物路径。 */
    private Path createScreenshotPath() throws IOException {
        Path directory = artifactRoot.resolve("desktop-screenshots").normalize();
        if (!directory.startsWith(artifactRoot)) {
            throw new IOException("Desktop screenshot artifact path escaped the artifact root.");
        }
        Files.createDirectories(directory);
        return directory.resolve("desktop-" + UUID.randomUUID() + ".png");
    }

    /** ArtifactUploadClient 只接受相对路径；此转换让工具结果天然不含节点磁盘位置。 */
    private String relativeArtifactPath(Path output) {
        Path normalized = output.toAbsolutePath().normalize();
        if (!normalized.startsWith(artifactRoot)) {
            throw new IllegalArgumentException("Desktop screenshot artifact path escaped the artifact root.");
        }
        return artifactRoot.relativize(normalized).toString().replace('\\', '/');
    }

    private static Path defaultArtifactRoot() {
        return Path.of(System.getProperty("java.io.tmpdir"), "cycbercompany-node", "artifacts");
    }

    /**
     * PowerShell 的 ConvertTo-Json 在只有一项时会返回对象、没有项目时可能返回空文本；
     * 这里统一成数组，并只挑选后续安全操作确实需要的元数据字段。
     *
     * <p>不能把原始 JSON 字符串直接透传：一旦输出很长就会在中间截断，模型既无法可靠解析，
     * 也可能误把缺失字段当成可执行定位条件。结构化、限量的结果则可以被 ToolResultBudget
     * 再次检查，形成两层输出预算。
     */
    private static List<Map<String, Object>> desktopMetadataRows(
            String raw,
            int maxRows,
            MetadataKind kind) throws IOException {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        JsonNode root = JSON.readTree(raw);
        List<JsonNode> source = new ArrayList<>();
        if (root.isArray()) {
            root.forEach(source::add);
        } else if (root.isObject()) {
            source.add(root);
        } else {
            throw new IOException("Windows desktop snapshot returned an invalid JSON shape.");
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (JsonNode item : source) {
            if (result.size() >= maxRows) {
                break;
            }
            if (!item.isObject()) {
                continue;
            }
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            Long processId = positiveLong(item, "processId", "Id", "id");
            if (processId != null) {
                row.put("processId", processId);
            }
            if (kind == MetadataKind.WINDOW) {
                putText(row, "processName", text(item, "processName", "ProcessName"));
                putText(row, "title", text(item, "title", "mainWindowTitle", "MainWindowTitle", "name", "Name"));
            } else {
                putText(row, "kind", text(item, "kind"));
                putText(row, "name", text(item, "name", "Name"));
                putText(row, "automationId", text(item, "automationId", "AutomationId"));
                putText(row, "controlType", text(item, "controlType", "ControlType"));
                putText(row, "className", text(item, "className", "ClassName"));
                JsonNode enabled = field(item, "enabled", "Enabled");
                if (enabled != null && enabled.isBoolean()) {
                    row.put("enabled", enabled.booleanValue());
                }
            }
            // 即使旧版 UI Automation 返回了异常行，也保留一个空对象以避免悄悄改变行数；
            // 后续 click/type 仍会因为缺少 processId 与选择器而在节点本地拒绝执行。
            result.add(Map.copyOf(row));
        }
        return List.copyOf(result);
    }

    private static Long positiveLong(JsonNode item, String... names) {
        JsonNode value = field(item, names);
        if (value == null) {
            return null;
        }
        try {
            long parsed = value.isNumber() ? value.longValue() : Long.parseLong(value.asText());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String text(JsonNode item, String... names) {
        JsonNode value = field(item, names);
        return value == null || value.isNull() ? null : truncateMetadata(value.asText());
    }

    private static JsonNode field(JsonNode item, String... names) {
        for (String name : names) {
            JsonNode value = item.get(name);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static void putText(Map<String, Object> target, String name, String value) {
        if (value != null && !value.isBlank()) {
            target.put(name, value);
        }
    }

    private static String truncateMetadata(String value) {
        return value.length() <= MAX_METADATA_TEXT_CHARS
                ? value
                : value.substring(0, MAX_METADATA_TEXT_CHARS);
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

    /** 可注入截图器，让 Windows API 的真实副作用能够在普通单元测试中被可靠验证。 */
    @FunctionalInterface
    interface ScreenCapture {
        ScreenCaptureResult capture(Path output) throws Exception;
    }

    record CommandResult(int exitCode, String output) {
    }

    record ScreenCaptureResult(int width, int height) {
    }

    private enum MetadataKind {
        WINDOW,
        CONTROL
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
