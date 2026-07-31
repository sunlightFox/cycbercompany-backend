package io.github.yourname.agentstudio.nodeclient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.microsoft.playwright.CLI;
import io.github.yourname.agentstudio.nodeclient.config.NodeConfigStore;
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
        HttpClient httpClient = HttpClient.newHttpClient();

        if (args.length == 0 || "--help".equals(args[0]) || "-h".equals(args[0])) {
            printUsage();
            return;
        }

        String command = args[0];
        Map<String, String> options = CliArgs.parse(args);
        NodeConfigStore configStore = new NodeConfigStore(objectMapper, configPath(options));
        if ("register".equals(command)) {
            register(options, configStore, objectMapper, httpClient);
            return;
        }
        if ("start".equals(command)) {
            start(configStore, objectMapper, httpClient);
            return;
        }
        if ("install-browsers".equals(command)) {
            // Playwright Java 包不含浏览器二进制文件，首次使用浏览器工具前须单独安装。
            CLI.main(new String[]{"install", "chromium"});
            return;
        }
        throw new IllegalArgumentException("Unknown command: " + command);
    }

    private static void register(
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
                workspace.toString());
        configStore.save(config);
        System.out.println("Node registered successfully.");
        System.out.println("nodeId=" + config.nodeId());
        System.out.println("workspace=" + config.workspaceRoot());
        System.out.println("config=" + configStore.path());
    }

    private static void start(NodeConfigStore configStore, ObjectMapper objectMapper, HttpClient httpClient) throws Exception {
        NodeConfig config = configStore.load();
        NodeWebSocketClient client = new NodeWebSocketClient(objectMapper, httpClient, config, SystemInfo.current());
        client.startBlocking();
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
                Agent Studio Java Node

                Commands:
                  register --server http://localhost:8080 --token <registrationToken> [--name my-pc] [--workspace path] [--config node-config.json]
                  start [--config node-config.json]
                  install-browsers
                """);
    }

    private static Path workspacePath(Map<String, String> options) {
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

    private static Path configPath(Map<String, String> options) {
        String configured = options.get("config");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        return defaultConfigPath();
    }
}
