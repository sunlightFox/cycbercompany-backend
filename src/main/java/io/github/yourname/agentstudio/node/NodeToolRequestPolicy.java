package io.github.yourname.agentstudio.node;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
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
    private static final List<String> PATH_PLACEHOLDERS = List.of(
            "<path>",
            "<absolute path>",
            "<file>",
            "<file path>",
            "<directory>",
            "<dir>",
            "<folder>",
            "<cwd>",
            "<workspace>",
            "<project root>",
            "<desktop>",
            "<desktoppath>",
            "<desktop path>",
            "<target>",
            "<destination>",
            "<source>");
    private static final List<String> PATH_ARGUMENT_NAMES = List.of(
            "path",
            "cwd",
            "source",
            "destination",
            "stdoutPath",
            "stderrPath");
    private static final List<Pattern> LIKELY_LONG_RUNNING_SERVER_COMMANDS = List.of(
            Pattern.compile("\\b(?:npm|pnpm|yarn|bun)(?:\\.cmd|\\.exe)?\\s+(?:run\\s+)?(?:dev|serve|start|preview|watch|storybook)\\b"),
            Pattern.compile("\\b(?:npm|pnpm|yarn|bun)(?:\\.cmd|\\.exe)?\\s+exec\\s+.*\\b(?:dev|serve|start|preview|watch)\\b"),
            Pattern.compile("\\b(?:(?:npx|bunx)(?:\\.cmd|\\.exe)?|npm(?:\\.cmd|\\.exe)?\\s+exec|pnpm(?:\\.cmd|\\.exe)?\\s+exec|yarn(?:\\.cmd|\\.exe)?\\s+dlx)\\s+(?:vite|next|nuxt|parcel|webpack-dev-server|http-server|live-server|storybook|nodemon|ts-node-dev)\\b"),
            Pattern.compile("\\b(?:vite|next|nuxt)\\s+(?:dev|preview)\\b"),
            Pattern.compile("\\b(?:ng|nx)\\s+serve\\b"),
            Pattern.compile("\\breact-scripts\\s+start\\b"),
            Pattern.compile("\\bpython(?:\\.exe)?\\s+-m\\s+http\\.server\\b"),
            Pattern.compile("\\bpython(?:\\.exe)?\\s+-m\\s+(?:uvicorn|gunicorn|hypercorn|flask)\\b"),
            Pattern.compile("\\b(?:uvicorn|gunicorn|hypercorn)\\b"),
            Pattern.compile("\\b(?:flask\\s+run|nodemon|ts-node-dev|vite-node)\\b"),
            Pattern.compile("\\bdotnet\\s+watch\\b"),
            Pattern.compile("\\b(?:gradle|gradlew|gradlew\\.bat|mvn|mvnw|mvnw\\.cmd)\\s+.*\\b(?:bootRun|spring-boot:run)\\b"));

    private final BrowserPolicyProperties browserPolicy;

    public NodeToolRequestPolicy(BrowserPolicyProperties browserPolicy) {
        this.browserPolicy = browserPolicy;
    }

    public Map<String, Object> prepare(String toolName, Map<String, Object> arguments) {
        Map<String, Object> prepared = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
        prepared.remove(BROWSER_POLICY_ARGUMENT);
        removeNullArguments(prepared);

        if ("browser.open".equals(toolName)) {
            String safeUrl = BrowserUrlPolicy.requireAllowed(stringValue(prepared.get("url")), browserPolicy);
            prepared.put("url", safeUrl);
            prepared.put(BROWSER_POLICY_ARGUMENT, Map.of(
                    ALLOWED_PRIVATE_HOSTS, browserPolicy.allowedPrivateHosts()));
        } else if ("browser.screenshot".equals(toolName)) {
            prepared.remove("path");
        }

        validatePowerShellCommand(toolName, prepared);
        validatePathPlaceholders(toolName, prepared);
        validateShortLivedShellCommand(toolName, prepared);
        validateManagedProcessCommand(toolName, prepared);
        NodeToolArgumentValidator.validate(toolName, prepared);
        return Map.copyOf(prepared);
    }

    private static void removeNullArguments(Map<String, Object> arguments) {
        if (arguments != null) {
            arguments.entrySet().removeIf(entry -> entry.getValue() == null);
        }
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

    private static void validatePathPlaceholders(String toolName, Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return;
        }
        for (String name : PATH_ARGUMENT_NAMES) {
            Object value = arguments.get(name);
            if (value instanceof String text && containsPathPlaceholder(text)) {
                throw new IllegalArgumentException(
                        toolName + " argument '" + name + "' contains an unreplaced placeholder. "
                                + "Use a concrete path returned by an inspection tool or provided by the user.");
            }
        }
        Object rawChanges = arguments.get("changes");
        if (rawChanges instanceof List<?> changes) {
            for (Object rawChange : changes) {
                if (rawChange instanceof Map<?, ?> change) {
                    Object value = change.get("path");
                    if (value instanceof String text && containsPathPlaceholder(text)) {
                        throw new IllegalArgumentException(
                                toolName + " argument 'changes[].path' contains an unreplaced placeholder. "
                                        + "Use a concrete path returned by an inspection tool or provided by the user.");
                    }
                }
            }
        }
    }

    private static boolean containsPathPlaceholder(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return PATH_PLACEHOLDERS.stream().anyMatch(normalized::contains);
    }

    private static void validateShortLivedShellCommand(String toolName, Map<String, Object> arguments) {
        if (!("shell.run".equals(toolName) || "system.shell.run".equals(toolName))) {
            return;
        }
        Object rawCommand = arguments == null ? null : arguments.get("command");
        if (!(rawCommand instanceof String command) || command.isBlank()) {
            return;
        }
        String normalized = normalizedCommand(command);
        if (isDetachedOrLongRunningShellCommand(normalized)) {
            throw new IllegalArgumentException(
                    toolName + " is for short-lived commands. Use process.start or system.process.start for long-running development servers or watch processes.");
        }
    }

    private static void validateManagedProcessCommand(String toolName, Map<String, Object> arguments) {
        if (!("process.start".equals(toolName) || "system.process.start".equals(toolName))) {
            return;
        }
        Object rawCommand = arguments == null ? null : arguments.get("command");
        if (!(rawCommand instanceof String command) || command.isBlank()) {
            return;
        }
        String normalized = normalizedCommand(command);
        if (normalized.contains("start-process")
                || normalized.contains("start-job")
                || normalized.contains("disown")
                || normalized.contains("setsid")
                || normalized.contains("nohup ")
                || normalized.matches("^(?:cmd(?:\\.exe)?\\s+/c\\s+)?start(?:\\s+|$).*")
                || normalized.endsWith(" &")) {
            throw new IllegalArgumentException(
                    toolName + " manages the process itself. Use a foreground command; do not use Start-Process, nohup, or a trailing '&'.");
        }
    }

    private static boolean isDetachedOrLongRunningShellCommand(String normalized) {
        return normalized.contains("start-job")
                || normalized.contains("start-process")
                || normalized.contains("disown")
                || normalized.contains("setsid")
                || normalized.contains("nohup ")
                || normalized.endsWith(" &")
                || normalized.matches("^(?:cmd(?:\\.exe)?\\s+/c\\s+)?start(?:\\s+|$).*")
                || dockerComposeUpWithoutDetach(normalized)
                || LIKELY_LONG_RUNNING_SERVER_COMMANDS.stream().anyMatch(pattern -> pattern.matcher(normalized).find());
    }

    private static boolean dockerComposeUpWithoutDetach(String normalized) {
        boolean composeUp = normalized.matches(".*\\bdocker(?:\\.exe)?\\s+compose\\b.*\\sup\\b.*")
                || normalized.matches(".*\\bdocker-compose(?:\\.exe)?\\b.*\\sup\\b.*");
        return composeUp && !normalized.matches(".*(?:^|\\s)(?:-d|--detach)(?:\\s|$).*");
    }

    private static String normalizedCommand(String command) {
        return command == null ? "" : command.toLowerCase(Locale.ROOT)
                .replaceAll("[\"']", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }
}
