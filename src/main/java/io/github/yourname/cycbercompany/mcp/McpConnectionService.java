package io.github.yourname.cycbercompany.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yourname.cycbercompany.config.AppProperties;
import io.github.yourname.cycbercompany.security.ActorContext;
import io.github.yourname.cycbercompany.tool.RegisteredTool;
import io.github.yourname.cycbercompany.tool.RiskLevel;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final McpToolInvocationRepository invocations;

    public McpConnectionService(
            AppProperties properties,
            ObjectMapper objectMapper,
            McpStdioClient stdioClient,
            McpToolInvocationRepository invocations) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.stdioClient = stdioClient;
        this.invocations = invocations;
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
        validateNpmInstall(command);
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
                !Boolean.FALSE.equals(command.enabled()),
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

    public McpConnectionView installRepository(InstallMcpRepositoryCommand command) {
        if (command == null || command.name() == null || command.name().isBlank()
                || command.endpoint() == null || command.endpoint().isBlank()) {
            throw new IllegalArgumentException("MCPMarket entry does not expose an installable endpoint.");
        }
        McpConnectionView created = create(new CreateMcpConnectionCommand(
                command.id(), command.name(), command.description(),
                command.transportType() == null ? McpTransportType.STREAMABLE_HTTP : command.transportType(),
                !Boolean.FALSE.equals(command.enabled()), null, List.of(), command.endpoint(), command.env(),
                Map.of("installType", "mcpmarket", "source", McpRepositoryService.MARKET_URL), List.of()));
        return Boolean.TRUE.equals(command.refreshTools()) ? refreshTools(created.id()) : created;
    }

    public List<McpConnectionView> importJson(ImportMcpConnectionsCommand command) {
        if (command == null || command.json() == null || command.json().isBlank()) {
            throw new IllegalArgumentException("MCP JSON is required.");
        }
        List<CreateMcpConnectionCommand> parsed = parseImportedConnections(command);
        if (parsed.isEmpty()) {
            throw new IllegalArgumentException("MCP JSON did not contain any server definitions.");
        }
        List<McpConnectionView> imported = new ArrayList<>();
        for (CreateMcpConnectionCommand connection : parsed) {
            String id = normalizeId(connection.id() == null || connection.id().isBlank()
                    ? connection.name()
                    : connection.id());
            McpConnectionView saved;
            if (Files.exists(configFile(id))) {
                if (!Boolean.TRUE.equals(command.overwrite())) {
                    throw new IllegalArgumentException("MCP connection already exists: " + id);
                }
                saved = update(id, new UpdateMcpConnectionCommand(
                        connection.name(),
                        connection.description(),
                        connection.transportType(),
                        connection.enabled(),
                        connection.command(),
                        connection.args(),
                        connection.endpoint(),
                        connection.env(),
                        connection.metadata(),
                        connection.tools()));
            } else {
                saved = create(connection);
            }
            if (Boolean.TRUE.equals(command.refreshTools())) {
                saved = refreshTools(saved.id());
            }
            imported.add(saved);
        }
        return imported;
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
        try {
            ensureExecutable(current);
            List<UpsertMcpToolCommand> discovered = stdioClient.listTools(toRuntimeConnection(current));
            StoredMcpConnection updated = current.withTools(mergeTools(current.id(), current.tools(), discovered));
            save(updated);
            return toView(updated);
        } catch (RuntimeException ex) {
            save(current.withLastError(discoveryError(ex)));
            throw ex;
        }
    }

    @Transactional(noRollbackFor = Exception.class)
    public McpToolCallResult callTool(
            String connectionId,
            String toolName,
            CallMcpToolCommand command,
            String runId,
            ActorContext actor) {
        return callTool(connectionId, toolName, command, runId, actor, false);
    }

    /** 仅供 ToolRouter 在精确审批已经持久化并批准后调用。 */
    public McpToolCallResult callToolAfterApproval(
            String connectionId,
            String toolName,
            CallMcpToolCommand command,
            String runId,
            ActorContext actor) {
        return callTool(connectionId, toolName, command, runId, actor, true);
    }

    private McpToolCallResult callTool(
            String connectionId,
            String toolName,
            CallMcpToolCommand command,
            String runId,
            ActorContext actor,
            boolean approvalGranted) {
        Map<String, Object> arguments = command == null || command.arguments() == null ? Map.of() : command.arguments();
        Instant now = Instant.now();
        McpToolInvocationEntity invocation = invocations.save(new McpToolInvocationEntity(
                "mcpinv_" + UUID.randomUUID(),
                actor.tenantId(),
                actor.userId(),
                runId,
                connectionId,
                toolName,
                String.join(",", arguments.keySet().stream().sorted().toList()),
                sha256(arguments),
                now));
        invocation.start(now);
        invocations.save(invocation);
        try {
            StoredMcpConnection connection = load(connectionId);
            ensureExecutable(connection);
            StoredMcpTool tool = connection.tools().stream()
                    .filter(candidate -> candidate.name().equals(toolName))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("MCP tool not found: " + toolName));
            if (!tool.enabled()) {
                throw new IllegalArgumentException("MCP tool is disabled: " + toolName);
            }
            if (tool.requiresApproval() && !approvalGranted) {
                invocation.deny("APPROVAL_REQUIRED", Instant.now());
                invocations.save(invocation);
                throw new IllegalStateException(
                        "MCP tool requires approval and cannot execute until the unified approval flow is available: " + toolName);
            }
            McpToolCallResult result = stdioClient.callTool(toRuntimeConnection(connection), tool.name(), arguments);
            if (result.error()) {
                invocation.fail("MCP_RESULT_ERROR", Instant.now());
            } else {
                invocation.succeed(result.content() == null ? 0 : result.content().size(), Instant.now());
            }
            invocations.save(invocation);
            return result;
        } catch (Exception ex) {
            if (invocation.status() != McpToolInvocationStatus.DENIED) {
                invocation.fail(ex.getClass().getSimpleName(), Instant.now());
                invocations.save(invocation);
            }
            throw ex;
        }
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

    public List<McpToolCallResult> callLikelySearchTools(
            List<String> selectedConnectionIds,
            String query,
            String runId,
            ActorContext actor) {
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
                    results.add(callTool(
                            connection.id(),
                            tool.name(),
                            new CallMcpToolCommand(Map.of("query", query)),
                            runId,
                            actor));
                } catch (Exception ignored) {
                    // A failed optional MCP source should not break the whole chat run.
                }
            }
        }
        return results;
    }

    @Transactional(readOnly = true)
    public List<McpToolInvocationView> listInvocations(ActorContext actor) {
        return invocations.findByTenantIdOrderByCreatedAtDesc(actor.tenantId()).stream()
                .map(McpToolInvocationView::from)
                .toList();
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
        McpConnectionStatus status = !stored.enabled()
                ? McpConnectionStatus.DISABLED
                : !stored.lastError().isBlank()
                        ? McpConnectionStatus.ERROR
                        : stored.tools().isEmpty()
                                ? McpConnectionStatus.NEEDS_DISCOVERY
                                : McpConnectionStatus.CONFIGURED;
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
                stored.updatedAt(),
                stored.lastError());
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
            // Discovery is allowed to refresh server-owned metadata, but it must
            // never silently overwrite an administrator's execution policy.
            merged.put(incoming.name(), current == null ? incoming : new StoredMcpTool(
                    current.id(),
                    incoming.name(),
                    incoming.description(),
                    incoming.inputSchema(),
                    current.riskLevel(),
                    current.requiresApproval(),
                    current.enabled(),
                    current.discoveredAt()));
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
                resolveRuntimeEnv(stored.id(), stored.env()));
    }

    private Map<String, String> resolveRuntimeEnv(String connectionId, Map<String, String> storedEnv) {
        if (storedEnv == null || storedEnv.isEmpty()) {
            return Map.of();
        }
        Map<String, String> resolved = new LinkedHashMap<>();
        storedEnv.forEach((key, value) -> {
            if (value != null && value.startsWith("env:")) {
                String envName = value.substring("env:".length()).trim();
                if (!envName.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                    throw new IllegalArgumentException("Invalid environment variable reference for MCP connection "
                            + connectionId + ": " + envName);
                }
                String envValue = System.getenv(envName);
                if (envValue == null) {
                    throw new IllegalStateException("MCP connection " + connectionId
                            + " requires environment variable " + envName + " but it is not set.");
                }
                resolved.put(key, envValue);
            } else if (value != null) {
                resolved.put(key, value);
            }
        });
        return resolved;
    }

    private List<CreateMcpConnectionCommand> parseImportedConnections(ImportMcpConnectionsCommand command) {
        try {
            JsonNode root = objectMapper.readTree(command.json());
            JsonNode servers = firstObject(root, "mcpServers", "servers");
            if (!servers.isMissingNode()) {
                List<CreateMcpConnectionCommand> connections = new ArrayList<>();
                servers.fields().forEachRemaining(entry -> connections.add(toCreateCommand(
                        entry.getKey(), entry.getValue(), command.enabled())));
                return connections;
            }
            if (root.isObject()) {
                String id = text(root, "id", text(root, "name", "mcp"));
                return List.of(toCreateCommand(id, root, command.enabled()));
            }
            throw new IllegalArgumentException("MCP JSON must be an object.");
        } catch (IOException ex) {
            throw new IllegalArgumentException("Invalid MCP JSON: " + ex.getMessage(), ex);
        }
    }

    private CreateMcpConnectionCommand toCreateCommand(String id, JsonNode server, Boolean enabledOverride) {
        if (server == null || !server.isObject()) {
            throw new IllegalArgumentException("MCP server definition must be an object: " + id);
        }
        String command = text(server, "command", "");
        String endpoint = text(server, "endpoint", text(server, "url", ""));
        McpTransportType transport = importedTransport(server, command, endpoint);
        boolean enabled = enabledOverride == null
                ? !server.has("enabled") || server.path("enabled").asBoolean(true)
                : enabledOverride;
        Map<String, String> metadata = stringMap(server.path("metadata"));
        metadata.putIfAbsent("importSource", "raw-json");
        return new CreateMcpConnectionCommand(
                id,
                text(server, "name", id),
                text(server, "description", ""),
                transport,
                enabled,
                command,
                stringList(server.path("args")),
                endpoint,
                stringMap(server.path("env")),
                metadata,
                List.of());
    }

    private static JsonNode firstObject(JsonNode root, String... fields) {
        for (String field : fields) {
            JsonNode value = root.path(field);
            if (value.isObject()) {
                return value;
            }
        }
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    private static McpTransportType importedTransport(JsonNode server, String command, String endpoint) {
        String raw = text(server, "transportType", text(server, "transport", text(server, "type", "")))
                .replace('-', '_')
                .toUpperCase(Locale.ROOT);
        if ("HTTP".equals(raw) || "STREAMABLE_HTTP".equals(raw)) {
            return McpTransportType.STREAMABLE_HTTP;
        }
        if ("SSE".equals(raw)) {
            return McpTransportType.SSE;
        }
        if ("STDIO".equals(raw)) {
            return McpTransportType.STDIO;
        }
        return command.isBlank() && !endpoint.isBlank() ? McpTransportType.STREAMABLE_HTTP : McpTransportType.STDIO;
    }

    private static List<String> stringList(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (node.isArray()) {
            List<String> values = new ArrayList<>();
            for (JsonNode value : node) {
                values.add(value.asText());
            }
            return values;
        }
        if (node.isTextual() && !node.asText().isBlank()) {
            return List.of(node.asText());
        }
        return List.of();
    }

    private static Map<String, String> stringMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return new LinkedHashMap<>();
        }
        Map<String, String> values = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (!value.isNull() && !value.isMissingNode()) {
                values.put(entry.getKey(), value.asText());
            }
        });
        return values;
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

    private static void validateNpmInstall(InstallNpmMcpServerCommand command) {
        if (!command.npmPackage().matches("^(?:@[a-z0-9][a-z0-9._-]*/)?[a-z0-9][a-z0-9._-]*$")) {
            throw new IllegalArgumentException("Invalid npm package name.");
        }
        if (command.packageArgs() == null) return;
        for (String argument : command.packageArgs()) {
            if (argument == null || argument.isBlank() || argument.matches(".*[&|<>^\\r\\n].*")) {
                throw new IllegalArgumentException("Invalid npm package argument.");
            }
        }
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

    private static String text(JsonNode node, String field, String fallback) {
        String value = node.path(field).asText("");
        return value.isBlank() ? fallback : value.trim();
    }

    private static String discoveryError(RuntimeException ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return "MCP tool discovery failed.";
        }
        String compact = message.replaceAll("[\\r\\n]+", " ").trim();
        return compact.length() <= 320 ? compact : compact.substring(0, 317) + "...";
    }

    private String sha256(Map<String, Object> arguments) {
        try {
            String json = objectMapper.writeValueAsString(arguments == null ? Map.of() : arguments);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | IOException ex) {
            throw new IllegalStateException("Cannot calculate MCP argument digest.", ex);
        }
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
                    metadata, tools, createdAt, Instant.now(), "");
        }

        StoredMcpConnection withLastError(String error) {
            return new StoredMcpConnection(
                    id, name, description, transportType, enabled, command, args, endpoint, env,
                    metadata, tools, createdAt, Instant.now(), error);
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
