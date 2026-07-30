package io.github.yourname.agentstudio.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yourname.agentstudio.config.AppProperties;
import io.github.yourname.agentstudio.tool.RegisteredTool;
import io.github.yourname.agentstudio.tool.RiskLevel;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Persistent management service for MCP connections and their tools.
 *
 * <p>This class manages configuration, discovery metadata and enablement
 * policy. It deliberately does not execute arbitrary MCP server commands yet;
 * execution belongs in a later runner/adapter that can enforce timeouts,
 * process isolation, secret handling and audit logging.
 */
@Service
public class McpConnectionService {

    private static final String FILE_SUFFIX = ".json";

    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final McpStdioClient stdioClient;

    public McpConnectionService(AppProperties properties, ObjectMapper objectMapper, McpStdioClient stdioClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.stdioClient = stdioClient;
    }

    @PostConstruct
    void ensureConfigDirectoryExists() throws IOException {
        Files.createDirectories(configDir());
    }

    public List<McpConnectionView> listConnections() {
        try {
            if (!Files.exists(configDir())) {
                return List.of();
            }
            try (var stream = Files.list(configDir())) {
                return stream
                        .filter(path -> path.getFileName().toString().endsWith(FILE_SUFFIX))
                        .map(this::readConfig)
                        .flatMap(Optional::stream)
                        .map(this::toView)
                        .sorted(Comparator.comparing(McpConnectionView::name, String.CASE_INSENSITIVE_ORDER))
                        .toList();
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to list MCP connections: " + ex.getMessage(), ex);
        }
    }

    public McpConnectionView getConnection(String id) {
        return toView(load(id));
    }

    public McpConnectionView create(CreateMcpConnectionCommand command) {
        String id = normalizeId(command.id() == null || command.id().isBlank() ? command.name() : command.id());
        Path path = configFile(id);
        if (Files.exists(path)) {
            throw new IllegalArgumentException("MCP connection already exists: " + id);
        }
        Instant now = Instant.now();
        StoredMcpConnection stored = new StoredMcpConnection(
                id,
                command.name().trim(),
                blankToEmpty(command.description()),
                command.transportType() == null ? McpTransportType.STDIO : command.transportType(),
                Boolean.TRUE.equals(command.enabled()),
                blankToEmpty(command.command()),
                command.args() == null ? List.of() : command.args(),
                blankToEmpty(command.endpoint()),
                command.env() == null ? Map.of() : new LinkedHashMap<>(command.env()),
                command.metadata() == null ? Map.of() : new LinkedHashMap<>(command.metadata()),
                normalizeTools(id, command.tools()),
                now,
                now,
                "");
        validateTransport(stored);
        save(stored);
        return toView(stored);
    }

    public McpConnectionView installNpm(InstallNpmMcpServerCommand command) {
        List<String> args = new ArrayList<>();
        String executable = windowsNpxCommand();
        if (isWindows()) {
            args.add("/d");
            args.add("/s");
            args.add("/c");
            args.add("npx -y " + command.npmPackage() + joinedPackageArgs(command.packageArgs()));
        } else {
            args.add("-y");
            args.add(command.npmPackage());
            if (command.packageArgs() != null) {
                args.addAll(command.packageArgs());
            }
        }
        McpConnectionView created = create(new CreateMcpConnectionCommand(
                command.id(),
                command.name(),
                command.description(),
                McpTransportType.STDIO,
                command.enabled(),
                executable,
                args,
                null,
                command.env(),
                Map.of("installType", "npm", "npmPackage", command.npmPackage()),
                List.of()));
        if (Boolean.TRUE.equals(command.refreshTools())) {
            return refreshTools(created.id());
        }
        return created;
    }

    public McpConnectionView update(String id, UpdateMcpConnectionCommand command) {
        StoredMcpConnection current = load(id);
        StoredMcpConnection updated = new StoredMcpConnection(
                current.id(),
                command.name() == null ? current.name() : command.name().trim(),
                command.description() == null ? current.description() : command.description(),
                command.transportType() == null ? current.transportType() : command.transportType(),
                command.enabled() == null ? current.enabled() : command.enabled(),
                command.command() == null ? current.command() : command.command(),
                command.args() == null ? current.args() : command.args(),
                command.endpoint() == null ? current.endpoint() : command.endpoint(),
                command.env() == null ? current.env() : new LinkedHashMap<>(command.env()),
                command.metadata() == null ? current.metadata() : new LinkedHashMap<>(command.metadata()),
                mergeTools(current.id(), current.tools(), command.tools()),
                current.createdAt(),
                Instant.now(),
                "");
        validateTransport(updated);
        save(updated);
        return toView(updated);
    }

    public void delete(String id) {
        Path path = configFile(id);
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("MCP connection not found: " + id);
        }
        try {
            Files.delete(path);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to delete MCP connection " + id + ": " + ex.getMessage(), ex);
        }
    }

    public McpConnectionView setConnectionEnabled(String id, boolean enabled) {
        StoredMcpConnection current = load(id);
        StoredMcpConnection updated = current.withEnabled(enabled);
        save(updated);
        return toView(updated);
    }

    public List<McpToolView> listTools(String connectionId) {
        return toView(load(connectionId)).tools();
    }

    public McpToolView upsertTool(String connectionId, UpsertMcpToolCommand command) {
        StoredMcpConnection current = load(connectionId);
        Map<String, StoredMcpTool> tools = new LinkedHashMap<>();
        for (StoredMcpTool tool : current.tools()) {
            tools.put(tool.name(), tool);
        }
        StoredMcpTool incoming = toStoredTool(current.id(), command);
        StoredMcpTool existing = tools.get(incoming.name());
        tools.put(incoming.name(), existing == null ? incoming : incoming.withDiscoveredAt(existing.discoveredAt()));
        StoredMcpConnection updated = current.withTools(new ArrayList<>(tools.values()));
        save(updated);
        return toView(tools.get(incoming.name()));
    }

    public McpToolView updateTool(String connectionId, String toolName, UpdateMcpToolCommand command) {
        StoredMcpConnection current = load(connectionId);
        List<StoredMcpTool> updatedTools = new ArrayList<>();
        StoredMcpTool updatedTool = null;
        for (StoredMcpTool tool : current.tools()) {
            if (tool.name().equals(toolName)) {
                updatedTool = new StoredMcpTool(
                        tool.id(),
                        tool.name(),
                        command.description() == null ? tool.description() : command.description(),
                        command.inputSchema() == null ? tool.inputSchema() : command.inputSchema(),
                        command.riskLevel() == null ? tool.riskLevel() : command.riskLevel(),
                        command.requiresApproval() == null ? tool.requiresApproval() : command.requiresApproval(),
                        command.enabled() == null ? tool.enabled() : command.enabled(),
                        tool.discoveredAt());
                updatedTools.add(updatedTool);
            } else {
                updatedTools.add(tool);
            }
        }
        if (updatedTool == null) {
            throw new IllegalArgumentException("MCP tool not found: " + toolName);
        }
        save(current.withTools(updatedTools));
        return toView(updatedTool);
    }

    public McpToolView setToolEnabled(String connectionId, String toolName, boolean enabled) {
        return updateTool(connectionId, toolName, new UpdateMcpToolCommand(null, null, null, null, enabled));
    }

    public void deleteTool(String connectionId, String toolName) {
        StoredMcpConnection current = load(connectionId);
        List<StoredMcpTool> remaining = current.tools().stream()
                .filter(tool -> !tool.name().equals(toolName))
                .toList();
        if (remaining.size() == current.tools().size()) {
            throw new IllegalArgumentException("MCP tool not found: " + toolName);
        }
        save(current.withTools(remaining));
    }

    public McpConnectionView refreshTools(String connectionId) {
        StoredMcpConnection current = load(connectionId);
        ensureExecutable(current);
        List<UpsertMcpToolCommand> discovered = stdioClient.listTools(toRuntimeConnection(current));
        StoredMcpConnection updated = current.withTools(mergeTools(current.id(), current.tools(), discovered));
        save(updated);
        return toView(updated);
    }

    public McpToolCallResult callTool(String connectionId, String toolName, CallMcpToolCommand command) {
        StoredMcpConnection connection = load(connectionId);
        ensureExecutable(connection);
        StoredMcpTool tool = connection.tools().stream()
                .filter(candidate -> candidate.name().equals(toolName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("MCP tool not found: " + toolName));
        if (!tool.enabled()) {
            throw new IllegalArgumentException("MCP tool is disabled: " + toolName);
        }
        Map<String, Object> arguments = command == null || command.arguments() == null ? Map.of() : command.arguments();
        return stdioClient.callTool(toRuntimeConnection(connection), tool.name(), arguments);
    }

    /**
     * Returns tools that are actually selectable by the agent runtime.
     *
     * <p>Both switches must be on: the MCP connection itself and the individual
     * tool. This gives admins a safe coarse kill-switch plus precise tool-level
     * control.
     */
    public List<RegisteredTool> enabledRegisteredTools() {
        return listConnections().stream()
                .filter(McpConnectionView::enabled)
                .flatMap(connection -> connection.tools().stream()
                        .filter(McpToolView::enabled)
                        .map(tool -> new RegisteredTool(
                                tool.id(),
                                "MCP " + connection.name() + " / " + tool.name() + ": " + tool.description(),
                                tool.riskLevel(),
                                tool.requiresApproval())))
                .toList();
    }

    public List<McpToolCallResult> callLikelySearchTools(List<String> selectedConnectionIds, String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        List<McpToolCallResult> results = new ArrayList<>();
        for (McpConnectionView connection : listConnections()) {
            if (!connection.enabled()) {
                continue;
            }
            if (selectedConnectionIds != null && !selectedConnectionIds.isEmpty() && !selectedConnectionIds.contains(connection.id())) {
                continue;
            }
            for (McpToolView tool : connection.tools()) {
                if (!tool.enabled() || !isSearchLikeTool(tool.name())) {
                    continue;
                }
                try {
                    results.add(callTool(connection.id(), tool.name(), new CallMcpToolCommand(Map.of("query", query))));
                } catch (Exception ignored) {
                    // A failed optional MCP source should not break the whole chat run.
                }
            }
        }
        return results;
    }

    private StoredMcpConnection load(String id) {
        Path path = configFile(id);
        return readConfig(path).orElseThrow(() -> new IllegalArgumentException("MCP connection not found: " + id));
    }

    private Optional<StoredMcpConnection> readConfig(Path path) {
        try {
            return Optional.of(objectMapper.readValue(path.toFile(), StoredMcpConnection.class));
        } catch (IOException ex) {
            return Optional.empty();
        }
    }

    private void save(StoredMcpConnection stored) {
        try {
            Files.createDirectories(configDir());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(configFile(stored.id()).toFile(), stored);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to save MCP connection " + stored.id() + ": " + ex.getMessage(), ex);
        }
    }

    private McpConnectionView toView(StoredMcpConnection stored) {
        McpConnectionStatus status = stored.enabled()
                ? (stored.tools().isEmpty() ? McpConnectionStatus.NEEDS_DISCOVERY : McpConnectionStatus.CONFIGURED)
                : McpConnectionStatus.DISABLED;
        return new McpConnectionView(
                stored.id(),
                stored.name(),
                stored.description(),
                stored.transportType(),
                stored.enabled(),
                status,
                stored.command(),
                stored.args(),
                stored.endpoint(),
                stored.env().keySet().stream().sorted().toList(),
                stored.metadata(),
                stored.tools().stream()
                        .map(this::toView)
                        .sorted(Comparator.comparing(McpToolView::name, String.CASE_INSENSITIVE_ORDER))
                        .toList(),
                stored.createdAt(),
                stored.updatedAt());
    }

    private McpToolView toView(StoredMcpTool tool) {
        return new McpToolView(
                tool.id(),
                tool.name(),
                tool.description(),
                tool.inputSchema(),
                tool.riskLevel(),
                tool.requiresApproval(),
                tool.enabled(),
                tool.discoveredAt());
    }

    private List<StoredMcpTool> normalizeTools(String connectionId, List<UpsertMcpToolCommand> commands) {
        if (commands == null) {
            return List.of();
        }
        Map<String, StoredMcpTool> tools = new LinkedHashMap<>();
        for (UpsertMcpToolCommand command : commands) {
            StoredMcpTool tool = toStoredTool(connectionId, command);
            tools.put(tool.name(), tool);
        }
        return new ArrayList<>(tools.values());
    }

    private List<StoredMcpTool> mergeTools(
            String connectionId, List<StoredMcpTool> existing, List<UpsertMcpToolCommand> updates) {
        if (updates == null) {
            return existing;
        }
        Map<String, StoredMcpTool> merged = new LinkedHashMap<>();
        for (StoredMcpTool tool : existing) {
            merged.put(tool.name(), tool);
        }
        for (UpsertMcpToolCommand update : updates) {
            StoredMcpTool incoming = toStoredTool(connectionId, update);
            StoredMcpTool current = merged.get(incoming.name());
            merged.put(incoming.name(), current == null ? incoming : incoming.withDiscoveredAt(current.discoveredAt()));
        }
        return new ArrayList<>(merged.values());
    }

    private StoredMcpTool toStoredTool(String connectionId, UpsertMcpToolCommand command) {
        String name = command.name().trim();
        return new StoredMcpTool(
                mcpToolId(connectionId, name),
                name,
                blankToEmpty(command.description()),
                command.inputSchema() == null || command.inputSchema().isBlank() ? "{}" : command.inputSchema(),
                command.riskLevel() == null ? RiskLevel.MEDIUM : command.riskLevel(),
                Boolean.TRUE.equals(command.requiresApproval()),
                !Boolean.FALSE.equals(command.enabled()),
                Instant.now());
    }

    private void validateTransport(StoredMcpConnection stored) {
        if (stored.transportType() == McpTransportType.STDIO && stored.command().isBlank()) {
            throw new IllegalArgumentException("STDIO MCP connections require a command.");
        }
        if (stored.transportType() != McpTransportType.STDIO && stored.endpoint().isBlank()) {
            throw new IllegalArgumentException(stored.transportType() + " MCP connections require an endpoint.");
        }
    }

    private void ensureExecutable(StoredMcpConnection stored) {
        if (!stored.enabled()) {
            throw new IllegalArgumentException("MCP connection is disabled: " + stored.id());
        }
        if (stored.transportType() != McpTransportType.STDIO) {
            throw new IllegalArgumentException("Only STDIO MCP execution is implemented now: " + stored.id());
        }
        validateTransport(stored);
    }

    private McpRuntimeConnection toRuntimeConnection(StoredMcpConnection stored) {
        return new McpRuntimeConnection(
                stored.id(),
                stored.name(),
                stored.transportType(),
                stored.command(),
                stored.args(),
                stored.endpoint(),
                resolveRuntimeEnv(stored.env()));
    }

    private Map<String, String> resolveRuntimeEnv(Map<String, String> storedEnv) {
        if (storedEnv == null || storedEnv.isEmpty()) {
            return Map.of();
        }
        Map<String, String> resolved = new LinkedHashMap<>();
        storedEnv.forEach((key, value) -> {
            if (value != null && value.startsWith("env:")) {
                String envName = value.substring("env:".length());
                String envValue = System.getenv(envName);
                if (envValue != null) {
                    resolved.put(key, envValue);
                }
            } else if (value != null) {
                resolved.put(key, value);
            }
        });
        return resolved;
    }

    private Path configFile(String id) {
        String safeId = normalizeId(id);
        Path resolved = configDir().resolve(safeId + FILE_SUFFIX).normalize();
        if (!resolved.startsWith(configDir())) {
            throw new IllegalArgumentException("Resolved MCP config path escapes the config directory");
        }
        return resolved;
    }

    private Path configDir() {
        AppProperties.McpStore mcp = properties.mcp();
        if (mcp != null && mcp.configDir() != null) {
            return mcp.configDir().toAbsolutePath().normalize();
        }
        Path dataDir = properties.dataDir() == null ? Path.of("./data") : properties.dataDir();
        return dataDir.resolve("mcp-connections").toAbsolutePath().normalize();
    }

    private static String mcpToolId(String connectionId, String toolName) {
        return "mcp:" + normalizeId(connectionId) + ":" + normalizeId(toolName);
    }

    private static boolean isSearchLikeTool(String name) {
        String normalized = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return normalized.contains("search") || normalized.contains("query") || normalized.contains("web");
    }

    private static String windowsNpxCommand() {
        return isWindows() ? "cmd.exe" : "npx";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String joinedPackageArgs(List<String> packageArgs) {
        if (packageArgs == null || packageArgs.isEmpty()) {
            return "";
        }
        return " " + String.join(" ", packageArgs);
    }

    private static String normalizeId(String value) {
        String id = (value == null ? "mcp" : value)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("^-+|-+$", "");
        return id.isBlank() ? "mcp" : id;
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private record StoredMcpConnection(
            String id,
            String name,
            String description,
            McpTransportType transportType,
            boolean enabled,
            String command,
            List<String> args,
            String endpoint,
            Map<String, String> env,
            Map<String, String> metadata,
            List<StoredMcpTool> tools,
            Instant createdAt,
            Instant updatedAt,
            String lastError) {

        StoredMcpConnection withEnabled(boolean enabled) {
            return new StoredMcpConnection(
                    id, name, description, transportType, enabled, command, args, endpoint, env,
                    metadata, tools, createdAt, Instant.now(), lastError);
        }

        StoredMcpConnection withTools(List<StoredMcpTool> tools) {
            return new StoredMcpConnection(
                    id, name, description, transportType, enabled, command, args, endpoint, env,
                    metadata, tools, createdAt, Instant.now(), lastError);
        }
    }

    private record StoredMcpTool(
            String id,
            String name,
            String description,
            String inputSchema,
            RiskLevel riskLevel,
            boolean requiresApproval,
            boolean enabled,
            Instant discoveredAt) {

        StoredMcpTool withDiscoveredAt(Instant discoveredAt) {
            return new StoredMcpTool(
                    id, name, description, inputSchema, riskLevel, requiresApproval, enabled, discoveredAt);
        }
    }

    public record McpRuntimeConnection(
            String id,
            String name,
            McpTransportType transportType,
            String command,
            List<String> args,
            String endpoint,
            Map<String, String> env) {
    }
}
