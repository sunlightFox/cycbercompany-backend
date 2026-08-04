package io.github.yourname.agentstudio.node;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 把模型或 API 提交的参数转换为服务端真正允许下发给节点的参数。
 *
 * <p>隐藏策略字段会先被删除再由服务端重建，因此调用方不能伪造私网放行规则。截图路径也
 * 不允许由调用方决定，节点只能把文件写入自己的受管 Artifact 目录。
 */
@Component
public final class NodeToolRequestPolicy {

    public static final String BROWSER_POLICY_ARGUMENT = "_agentStudioBrowserPolicy";
    public static final String ALLOWED_PRIVATE_HOSTS = "allowedPrivateHosts";

    private final BrowserPolicyProperties browserPolicy;

    public NodeToolRequestPolicy(BrowserPolicyProperties browserPolicy) {
        this.browserPolicy = browserPolicy;
    }

    public Map<String, Object> prepare(String toolName, Map<String, Object> arguments) {
        Map<String, Object> prepared = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
        prepared.remove(BROWSER_POLICY_ARGUMENT);

        if ("browser.open".equals(toolName)) {
            String safeUrl = BrowserUrlPolicy.requireAllowed(stringValue(prepared.get("url")), browserPolicy);
            prepared.put("url", safeUrl);
            prepared.put(BROWSER_POLICY_ARGUMENT, Map.of(
                    ALLOWED_PRIVATE_HOSTS, browserPolicy.allowedPrivateHosts()));
        } else if ("browser.screenshot".equals(toolName)) {
            prepared.remove("path");
        }

        validatePowerShellCommand(toolName, prepared);
        NodeToolArgumentValidator.validate(toolName, prepared);
        return Map.copyOf(prepared);
    }

    /**
     * Reject a command that would only approve a truncated PowerShell invocation.
     *
     * <p>On Windows the node shell is {@code cmd.exe}; a model call such as
     * {@code powershell -NoProfile -Command } is therefore not a harmless no-op. It launches
     * PowerShell without a script, produces help text, and leaves a misleading failed approval in
     * the run history. Rejecting it before the approval gate gives the model a structured failure
     * so it can resend the complete command (normally through {@code cmd /c}).
     */
    private static void validatePowerShellCommand(String toolName, Map<String, Object> arguments) {
        if (!("shell.run".equals(toolName) || "system.shell.run".equals(toolName))) {
            return;
        }
        Object rawCommand = arguments == null ? null : arguments.get("command");
        if (!(rawCommand instanceof String command)) {
            return;
        }
        String trimmed = command.trim();
        if (trimmed.isEmpty() || !startsWithPowerShell(trimmed)) {
            return;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        int flag = lower.indexOf("-command");
        if (flag < 0 || !isCommandFlagBoundary(lower, flag)) {
            return;
        }
        String script = trimmed.substring(flag + "-command".length()).trim();
        if (script.isEmpty() || "\"\"".equals(script) || "''".equals(script)) {
            throw new IllegalArgumentException(
                    "PowerShell -Command is incomplete. Resend the full script; on Windows use "
                            + "cmd /c powershell ... when nested quotes are required.");
        }
    }

    private static boolean startsWithPowerShell(String command) {
        String candidate = command.trim();
        String lower = candidate.toLowerCase(Locale.ROOT);
        if (lower.startsWith("cmd /c ") || lower.startsWith("cmd.exe /c ")) {
            candidate = candidate.substring(candidate.indexOf("/c") + 2).trim();
        }
        String executable = candidate.split("\\s+", 2)[0]
                .replace("\"", "")
                .replace("'", "")
                .toLowerCase(Locale.ROOT);
        return executable.equals("powershell")
                || executable.equals("powershell.exe")
                || executable.equals("pwsh")
                || executable.equals("pwsh.exe");
    }

    private static boolean isCommandFlagBoundary(String value, int index) {
        boolean before = index == 0 || Character.isWhitespace(value.charAt(index - 1));
        int end = index + "-command".length();
        boolean after = end == value.length() || Character.isWhitespace(value.charAt(end));
        return before && after;
    }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }
}
