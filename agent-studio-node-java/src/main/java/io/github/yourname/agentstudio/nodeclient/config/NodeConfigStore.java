package io.github.yourname.agentstudio.nodeclient.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yourname.agentstudio.nodeclient.NodeConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class NodeConfigStore {

    // 文件系统操作集中于此；未来替换为系统密钥库时，命令入口不需要理解存储细节。

    private final ObjectMapper objectMapper;
    private final Path path;

    public NodeConfigStore(ObjectMapper objectMapper, Path path) {
        this.objectMapper = objectMapper;
        this.path = path;
    }

    public Path path() {
        return path;
    }

    public void save(NodeConfig config) throws IOException {
        // register 首次运行时父目录通常不存在，保存前先创建目录。
        Files.createDirectories(path.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), config);
    }

    public NodeConfig load() throws IOException {
        // 给出明确的引导错误，避免后续因空配置连接失败而难以定位原因。
        if (!Files.exists(path)) {
            throw new IllegalStateException("Node config not found. Run register first: " + path);
        }
        return objectMapper.readValue(path.toFile(), NodeConfig.class);
    }
}
