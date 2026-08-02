package io.github.yourname.agentstudio.nodeclient.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yourname.agentstudio.nodeclient.NodeConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NodeConfigStoreTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void storesTheLongLivedNodeSecretInsideAProtectedEnvelope() throws Exception {
        Path path = temporaryDirectory.resolve("config.json");
        NodeConfig config = sampleConfig();
        NodeConfigStore store = new NodeConfigStore(new ObjectMapper(), path, new ReversibleTestProtector());

        store.save(config);

        String stored = Files.readString(path);
        // 回归重点：无论外层 JSON 如何格式化，敏感长期凭证都不能原样落盘。
        assertFalse(stored.contains(config.nodeSecret()));
        assertTrue(stored.contains("test-reversible-v1"));
        assertTrue(stored.contains("ciphertext"));
        assertEquals(config, store.load());
    }

    @Test
    void migratesLegacyPlaintextConfigurationWhenAProtectionProviderIsAvailable() throws Exception {
        Path path = temporaryDirectory.resolve("legacy.json");
        NodeConfig config = sampleConfig();
        ObjectMapper mapper = new ObjectMapper();
        mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), config);
        NodeConfigStore store = new NodeConfigStore(mapper, path, new ReversibleTestProtector());

        assertEquals(config, store.load());

        String migrated = Files.readString(path);
        assertTrue(migrated.contains("test-reversible-v1"));
        assertFalse(migrated.contains(config.nodeSecret()));
    }

    @Test
    void rejectsAProtectedConfigurationForAnUnavailableProvider() throws Exception {
        Path path = temporaryDirectory.resolve("other-user.json");
        NodeConfigStore writer = new NodeConfigStore(new ObjectMapper(), path, new ReversibleTestProtector());
        writer.save(sampleConfig());
        NodeConfigStore reader = new NodeConfigStore(new ObjectMapper(), path, new DifferentTestProtector());

        IOException error = assertThrows(IOException.class, reader::load);

        assertTrue(error.getMessage().contains("different operating-system user or platform"));
    }

    private static NodeConfig sampleConfig() {
        return new NodeConfig(
                "http://127.0.0.1:8080",
                "node-1",
                "ns_this_must_not_be_plaintext",
                "ws://127.0.0.1:8080/api/v1/node-channel",
                "test-node",
                "C:/workspace",
                "WORKSPACE");
    }

    /**
     * 测试替身只验证存储格式和迁移逻辑，不冒充 Windows DPAPI；实际 DPAPI 由 Windows 系统 API
     * 执行，避免单元测试依赖当前机器的登录会话。
     */
    private static class ReversibleTestProtector implements NodeConfigProtector {

        @Override
        public String protectionId() {
            return "test-reversible-v1";
        }

        @Override
        public boolean protectsAtRest() {
            return true;
        }

        @Override
        public byte[] protect(byte[] plaintext) {
            return transform(plaintext);
        }

        @Override
        public byte[] unprotect(byte[] ciphertext) {
            return transform(ciphertext);
        }

        private static byte[] transform(byte[] input) {
            byte[] result = input.clone();
            for (int index = 0; index < result.length; index++) {
                result[index] ^= (byte) 0x5A;
            }
            return result;
        }
    }

    private static final class DifferentTestProtector extends ReversibleTestProtector {

        @Override
        public String protectionId() {
            return "another-user-v1";
        }
    }
}
