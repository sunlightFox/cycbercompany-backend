package io.github.yourname.cycbercompany.nodeclient.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.yourname.cycbercompany.nodeclient.NodeConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Base64;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;

public class NodeConfigStore {

    private static final int MAX_CONFIG_BYTES = 64 * 1024;
    private static final String FORMAT = "cycbercompany-node-config";
    private static final int FORMAT_VERSION = 1;

    private final ObjectMapper objectMapper;
    private final Path path;
    private final NodeConfigProtector protector;

    public NodeConfigStore(ObjectMapper objectMapper, Path path) {
        this(objectMapper, path, NodeConfigProtector.forCurrentPlatform());
    }

    /**
     * 包级构造器供同包回归测试注入可逆假保护器；生产入口始终使用当前平台实现。
     */
    NodeConfigStore(ObjectMapper objectMapper, Path path, NodeConfigProtector protector) {
        this.objectMapper = objectMapper;
        this.path = path.toAbsolutePath().normalize();
        this.protector = protector;
    }

    public Path path() {
        return path;
    }

    public void save(NodeConfig config) throws IOException {
        if (config == null) {
            throw new IllegalArgumentException("Node config is required.");
        }
        byte[] plainJson = objectMapper.writeValueAsBytes(config);
        byte[] output = protector.protectsAtRest()
                ? protectedEnvelope(plainJson)
                : plainJson;
        writeAtomically(output);
    }

    public NodeConfig load() throws IOException {
        // 给出明确的引导错误，避免后续因空配置连接失败而难以定位原因。
        if (!Files.exists(path)) {
            throw new IllegalStateException("Node config not found. Run register first: " + path);
        }
        byte[] stored = readBounded(path);
        JsonNode root = objectMapper.readTree(stored);
        if (root == null || !root.isObject()) {
            throw new IOException("Node config must contain one JSON object.");
        }
        if (root.has("protection")) {
            return readProtected(root);
        }

        // 旧版本 Windows 节点曾把 nodeSecret 直接写入 JSON。成功读到旧配置后立即迁移，
        // 确保用户只需正常执行一次 start 就能消除长期明文凭证。
        NodeConfig legacy = objectMapper.treeToValue(root, NodeConfig.class);
        if (protector.protectsAtRest()) {
            save(legacy);
        }
        return legacy;
    }

    private byte[] protectedEnvelope(byte[] plainJson) throws IOException {
        byte[] ciphertext = protector.protect(plainJson);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("format", FORMAT);
        envelope.put("version", FORMAT_VERSION);
        envelope.put("protection", protector.protectionId());
        envelope.put("ciphertext", Base64.getEncoder().encodeToString(ciphertext));
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(envelope);
    }

    private NodeConfig readProtected(JsonNode root) throws IOException {
        if (!FORMAT.equals(root.path("format").asText()) || root.path("version").asInt(-1) != FORMAT_VERSION) {
            throw new IOException("Node config uses an unsupported protected-file format.");
        }
        String protection = root.path("protection").asText();
        if (!protector.protectsAtRest() || !protector.protectionId().equals(protection)) {
            throw new IOException("Node config is protected for a different operating-system user or platform.");
        }
        String encoded = root.path("ciphertext").asText();
        if (encoded.isBlank()) {
            throw new IOException("Protected node config does not contain ciphertext.");
        }
        try {
            byte[] ciphertext = Base64.getDecoder().decode(encoded);
            return objectMapper.readValue(protector.unprotect(ciphertext), NodeConfig.class);
        } catch (IllegalArgumentException ex) {
            throw new IOException("Protected node config contains invalid ciphertext.", ex);
        }
    }

    private void writeAtomically(byte[] content) throws IOException {
        Path parent = path.getParent();
        if (parent == null) {
            throw new IOException("Node config must have a parent directory.");
        }
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, "." + path.getFileName(), ".tmp");
        try {
            Files.write(temporary, content);
            restrictToCurrentUser(temporary);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                // 少数网络盘不支持原子移动；仍使用同目录临时文件，避免直接截断旧配置。
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            restrictToCurrentUser(path);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static byte[] readBounded(Path file) throws IOException {
        long size = Files.size(file);
        if (size < 1 || size > MAX_CONFIG_BYTES) {
            throw new IOException("Node config size is invalid.");
        }
        return Files.readAllBytes(file);
    }

    /** POSIX 节点即使尚未接入系统密钥库，也只允许当前账户读写配置文件。 */
    private static void restrictToCurrentUser(Path file) {
        try {
            if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(file, EnumSet.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE));
            }
        } catch (Exception ignored) {
            // 文件系统不支持 POSIX ACL 时不能阻断注册；Windows DPAPI 仍然保护 nodeSecret。
        }
    }
}
