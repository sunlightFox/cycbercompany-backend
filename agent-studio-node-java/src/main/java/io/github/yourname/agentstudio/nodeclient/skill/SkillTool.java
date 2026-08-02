package io.github.yourname.agentstudio.nodeclient.skill;

import io.github.yourname.agentstudio.nodeclient.runtime.ToolExecutionResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 节点上的 Skill 资源读取和受限脚本执行工具。 */
public final class SkillTool {

    private static final int MAX_RESOURCE_BYTES = 128 * 1024;
    private static final int MAX_RESOURCE_CHARS = 32_000;
    private final SkillBundleCache cache;
    private final SkillWorkspaceManager workspaces;
    private final DockerSkillRuntime runtime;

    public SkillTool(SkillBundleCache cache, SkillWorkspaceManager workspaces, DockerSkillRuntime runtime) {
        this.cache = cache;
        this.workspaces = workspaces;
        this.runtime = runtime;
    }

    public Map<String, String> runtimes() {
        return runtime.runtimes();
    }

    public boolean scriptRuntimeAvailable() {
        return !runtime.runtimes().isEmpty();
    }

    public ToolExecutionResult readResource(Map<String, Object> arguments) {
        try {
            CachedSkillBundle bundle = ensure(arguments);
            String resource = required(arguments, "path");
            Path file = safePath(bundle.contentRoot(), resource);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) {
                return ToolExecutionResult.failure("Skill resource does not exist or is not a regular file: " + resource);
            }
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length > MAX_RESOURCE_BYTES) {
                return ToolExecutionResult.failure("Skill resource exceeds the text read limit: " + resource);
            }
            String text = new String(bytes, StandardCharsets.UTF_8);
            int requested = integer(arguments.get("maxChars"), MAX_RESOURCE_CHARS, 1, MAX_RESOURCE_CHARS);
            boolean truncated = text.length() > requested;
            if (truncated) text = text.substring(0, requested);
            return ToolExecutionResult.success(Map.of(
                    "skillId", bundle.skillId(),
                    "releaseDigest", bundle.releaseDigest(),
                    "path", resource.replace('\\', '/'),
                    "content", text,
                    "truncated", truncated));
        } catch (Exception ex) {
            return ToolExecutionResult.failure(message(ex));
        }
    }

    public ToolExecutionResult runScript(String runId, Map<String, Object> arguments) {
        try {
            if (runId == null || runId.isBlank()) {
                return ToolExecutionResult.failure("Skill script execution requires a backend-owned Run ID.");
            }
            String network = required(arguments, "network");
            if (!"none".equalsIgnoreCase(network)) {
                return ToolExecutionResult.failure("The first Docker Skill runtime supports only network=none.");
            }
            CachedSkillBundle bundle = ensure(arguments);
            String entrypoint = required(arguments, "entrypoint").replace('\\', '/');
            Path script = safePath(bundle.contentRoot(), entrypoint);
            if (!entrypoint.startsWith("scripts/")
                    || !Files.isRegularFile(script, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(script)) {
                return ToolExecutionResult.failure("Skill script entrypoint is not a verified file under scripts/: " + entrypoint);
            }
            String runtimeName = required(arguments, "runtime").toLowerCase(java.util.Locale.ROOT);
            if (!runtime.supports(runtimeName)) {
                return ToolExecutionResult.failure("Docker Skill runtime is unavailable for: " + runtimeName);
            }
            Path workspace = workspaces.materialize(runId, bundle);
            DockerSkillRuntime.RuntimeResult result = runtime.run(
                    runtimeName,
                    bundle.contentRoot(),
                    workspace,
                    entrypoint,
                    stringList(arguments.get("arguments")),
                    integer(arguments.get("timeoutSeconds"), 60, 1, 120));
            Map<String, Object> value = new java.util.LinkedHashMap<>();
            value.put("skillId", bundle.skillId());
            value.put("releaseDigest", bundle.releaseDigest());
            value.put("entrypoint", entrypoint);
            value.put("runtime", runtimeName);
            value.put("exitCode", result.exitCode());
            value.put("timedOut", result.timedOut());
            value.put("output", result.output());
            return result.succeeded()
                    ? ToolExecutionResult.success(value)
                    : ToolExecutionResult.failure("Skill script failed: " + result.output());
        } catch (Exception ex) {
            return ToolExecutionResult.failure(message(ex));
        }
    }

    private CachedSkillBundle ensure(Map<String, Object> arguments) {
        return cache.ensure(
                required(arguments, "skillId"),
                required(arguments, "releaseDigest"),
                required(arguments, "bundleDigest"));
    }

    private static Path safePath(Path root, String requested) {
        Path relative = Path.of(requested.replace('\\', '/')).normalize();
        if (relative.isAbsolute() || relative.startsWith("..") || requested.contains(":")) {
            throw new IllegalArgumentException("Unsafe Skill relative path: " + requested);
        }
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("Skill path escaped the verified Bundle.");
        }
        return resolved;
    }

    private static String required(Map<String, Object> arguments, String name) {
        Object value = arguments == null ? null : arguments.get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("Skill tool argument '" + name + "' must be a non-empty string.");
        }
        return text.trim();
    }

    private static List<String> stringList(Object value) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> values)) {
            throw new IllegalArgumentException("Skill script arguments must be an array of strings.");
        }
        List<String> result = new ArrayList<>();
        for (Object item : values) {
            if (!(item instanceof String text)) {
                throw new IllegalArgumentException("Skill script arguments must be an array of strings.");
            }
            result.add(text);
        }
        return List.copyOf(result);
    }

    private static int integer(Object value, int fallback, int minimum, int maximum) {
        int parsed = value instanceof Number number ? number.intValue() : fallback;
        return Math.max(minimum, Math.min(maximum, parsed));
    }

    private static String message(Exception ex) {
        return ex.getMessage() == null || ex.getMessage().isBlank()
                ? ex.getClass().getSimpleName()
                : ex.getMessage();
    }
}
