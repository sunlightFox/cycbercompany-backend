package io.github.yourname.agentstudio.nodeclient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.microsoft.playwright.CLI;
import io.github.yourname.agentstudio.nodeclient.config.NodeConfigStore;
import io.github.yourname.agentstudio.nodeclient.config.NodeProcessLock;
import io.github.yourname.agentstudio.nodeclient.protocol.NodeRegistrar;
import io.github.yourname.agentstudio.nodeclient.transport.NodeWebSocketClient;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.Map;

/**
 * Java 节点客户端入口。
 *
 * <p>第一版保持极简 CLI：
 * - register：使用后端一次性注册令牌换取 nodeId/nodeSecret；
 * - start：读取本地配置，连接后端 WebSocket，并上报节点能力。
 */
public class AgentStudioNodeApplication {

    public static void main(String[] args) throws Exception {
        // 节点客户端不使用 Spring 容器，依赖在入口显式组装，便于观察完整运行依赖。
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        // The local development entry point is commonly a Vite proxy. Pin the node transport to
        // HTTP/1.1 because its HTTP/2 upgrade path can be closed by that proxy before a response.
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        if (args.length == 0 || "--help".equals(args[0]) || "-h".equals(args[0])) {
            printUsage();
            return;
        }

        // jpackage prepends command-line arguments supplied by the user before its configured
        // default arguments. Treat an option-first invocation as the packaged GUI command so a
        // support/deployment shortcut can override --server, --workspace, or --config.
        String command = commandFor(args);
        Map<String, String> options = CliArgs.parse(args);
        NodeConfigStore configStore = new NodeConfigStore(objectMapper, configPath(options));
        if ("register".equals(command)) {
            register(options, configStore, objectMapper, httpClient);
            return;
        }
        if ("start".equals(command)) {
            start(options, configStore, objectMapper, httpClient);
            return;
        }
        if ("start-local".equals(command)) {
            startLocal(options, new NodeConfigStore(objectMapper, localConfigPath(options)), objectMapper, httpClient);
            return;
        }
        if ("auto".equals(command)) {
            autoStart(options, objectMapper, httpClient);
            return;
        }
        if ("gui".equals(command)) {
            NodeClientWindow.show(options, objectMapper, httpClient);
            return;
        }
        if ("install-browsers".equals(command)) {
            // Playwright Java 包不含浏览器二进制文件，首次使用浏览器工具前须单独安装。
            CLI.main(new String[]{"install", "chromium"});
            return;
        }
        throw new IllegalArgumentException("Unknown command: " + command);
    }

    static void register(
            Map<String, String> options,
            NodeConfigStore configStore,
            ObjectMapper objectMapper,
            HttpClient httpClient) throws Exception {
        String server = required(options, "server");
        String token = required(options, "token");
        String name = options.getOrDefault("name", SystemInfo.defaultNodeName());

        // 注册令牌仅用于这一次换取长期凭证，后续 start 不会再使用它。
        NodeRegistrar registrar = new NodeRegistrar(objectMapper, httpClient);
        NodeConfig registered = registrar.register(server, token, name, SystemInfo.current());
        Path workspace = workspacePath(options);
        NodeConfig config = new NodeConfig(
                registered.serverUrl(),
                registered.nodeId(),
                registered.nodeSecret(),
                registered.websocketUrl(),
                registered.name(),
                workspace.toString(),
                NodeAccessMode.from(options.get("access")).name());
        configStore.save(config);
        System.out.println("Node registered successfully.");
        System.out.println("nodeId=" + config.nodeId());
        System.out.println("workspace=" + config.workspaceRoot());
        System.out.println("access=" + config.resolvedAccessMode().name().toLowerCase(java.util.Locale.ROOT));
        System.out.println("config=" + configStore.path());
    }

    static void start(
            Map<String, String> options,
            NodeConfigStore configStore,
            ObjectMapper objectMapper,
            HttpClient httpClient) throws Exception {
        start(options, configStore, objectMapper, httpClient, ignored -> { });
    }

    static void start(
            Map<String, String> options,
            NodeConfigStore configStore,
            ObjectMapper objectMapper,
            HttpClient httpClient,
            java.util.function.Consumer<NodeWebSocketClient> clientReady) throws Exception {
        try (NodeProcessLock ignored = NodeProcessLock.acquire(configStore.path())) {
            startWithAcquiredProcessLock(options, configStore, objectMapper, httpClient, clientReady);
        }
    }

    /** Starts a node while its caller holds the identity lock across provisioning and connection. */
    static void startWithAcquiredProcessLock(
            Map<String, String> options,
            NodeConfigStore configStore,
            ObjectMapper objectMapper,
            HttpClient httpClient,
            java.util.function.Consumer<NodeWebSocketClient> clientReady) throws Exception {
        NodeConfig config = configStore.load();
        NodeWebSocketClient client = new NodeWebSocketClient(
                objectMapper,
                httpClient,
                config,
                SystemInfo.current(),
                optionalDesktopRoot(options.get("desktop-root")));
        clientReady.accept(client);
        client.startBlocking();
    }

    private static void startLocal(
            Map<String, String> options,
            NodeConfigStore configStore,
            ObjectMapper objectMapper,
            HttpClient httpClient) throws Exception {
        // The loopback launcher uses this headless command. Match the packaged GUI's
        // bounded bootstrap recovery so a just-restarted backend does not turn an
        // otherwise automatic local task into an immediate user-visible failure.
        try (NodeProcessLock ignored = NodeProcessLock.acquire(configStore.path())) {
            BootstrapRetryPolicy.execute(
                    () -> provisionLocal(options, configStore, objectMapper, httpClient),
                    () -> Thread.currentThread().isInterrupted(),
                    nextAttempt -> System.err.println(
                            "Local control plane is not ready; retrying bootstrap "
                                    + nextAttempt + "/" + BootstrapRetryPolicy.MAX_ATTEMPTS + "..."),
                    Thread::sleep);
            startWithAcquiredProcessLock(options, configStore, objectMapper, httpClient, ignoredClient -> { });
        }
    }

    static void provisionLocal(
            Map<String, String> options,
            NodeConfigStore configStore,
            ObjectMapper objectMapper,
            HttpClient httpClient) throws Exception {
        String server = required(options, "server");
        String name = options.getOrDefault("name", "This computer");
        // Provision on every launch. This keeps a local companion self-healing when the
        // server data directory is reset or a prior local credential has been rotated.
        // The bootstrap endpoint is loopback-only and reuses the same managed-local identity.
        NodeConfig registered = new NodeRegistrar(objectMapper, httpClient)
                .bootstrapLocalExecutor(server, name, SystemInfo.current());
        NodeConfig config = new NodeConfig(
                registered.serverUrl(),
                registered.nodeId(),
                registered.nodeSecret(),
                registered.websocketUrl(),
                registered.name(),
                workspacePath(options).toString(),
                NodeAccessMode.SYSTEM.name());
        configStore.save(config);
    }

    /** First-run registration for the packaged Windows client, then normal reconnects. */
    private static void autoStart(
            Map<String, String> options,
            ObjectMapper objectMapper,
            HttpClient httpClient) throws Exception {
        NodeConfigStore configStore = new NodeConfigStore(objectMapper, configPath(options));
        if (!java.nio.file.Files.exists(configStore.path())) {
            String server = firstNonBlank(options.get("server"), System.getenv("AGENT_STUDIO_SERVER"));
            String token = firstNonBlank(options.get("token"), System.getenv("AGENT_STUDIO_REGISTRATION_TOKEN"));
            if (server == null || token == null) {
                throw new IllegalArgumentException(
                        "First launch requires --server/--token or AGENT_STUDIO_SERVER/AGENT_STUDIO_REGISTRATION_TOKEN.");
            }
            register(optionsWith(options, "server", server, "token", token), configStore, objectMapper, httpClient);
        }
        start(options, configStore, objectMapper, httpClient);
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : (second != null && !second.isBlank() ? second : null);
    }

    static String commandFor(String[] args) {
        if (args == null || args.length == 0) {
            throw new IllegalArgumentException("A node command is required.");
        }
        return args[0].startsWith("--") ? "gui" : args[0];
    }

    private static Map<String, String> optionsWith(Map<String, String> options, String key1, String value1,
                                                    String key2, String value2) {
        Map<String, String> copy = new java.util.LinkedHashMap<>(options);
        copy.put(key1, value1);
        copy.put(key2, value2);
        return copy;
    }

    private static String required(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required option --" + name);
        }
        return value;
    }

    private static Path defaultConfigPath() {
        return Path.of(System.getProperty("user.home"), ".agent-studio-node", "config.json");
    }

    private static void printUsage() {
        System.out.println("""
                CycberCompany Java Node

                Commands:
                  register --server http://localhost:8080 --token <registrationToken> [--name my-pc] [--workspace path] [--access workspace|system] [--config node-config.json]
                  start [--config node-config.json] [--desktop-root path]
                  start-local --server http://localhost:8080 [--name "This computer"] [--workspace path] [--config local-executor.json]
                  auto [--server URL] [--token registrationToken] [--workspace path] [--config node-config.json]
                  gui [--config node-config.json]
                  install-browsers
                """);
    }

    static Path workspacePath(Map<String, String> options) {
        String configured = options.get("workspace");
        Path workspace = configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.dir"))
                : Path.of(configured);
        workspace = workspace.toAbsolutePath().normalize();
        if (!java.nio.file.Files.isDirectory(workspace)) {
            throw new IllegalArgumentException("Workspace must be an existing directory: " + workspace);
        }
        return workspace;
    }

    static Path configPath(Map<String, String> options) {
        String configured = options.get("config");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        return defaultConfigPath();
    }

    static Path localConfigPath(Map<String, String> options) {
        String configured = options.get("config");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.home"), ".agent-studio-node", "local-executor.json");
    }

    private static Path optionalDesktopRoot(String configured) {
        if (configured == null || configured.isBlank()) {
            return null;
        }
        Path root = Path.of(configured).toAbsolutePath().normalize();
        if (!java.nio.file.Files.isDirectory(root)) {
            throw new IllegalArgumentException("Desktop root must be an existing directory: " + root);
        }
        return root;
    }
}
