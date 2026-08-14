package io.github.yourname.cycbercompany.nodeclient.config;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NodeProcessLockTest {

    @TempDir
    Path tempDir;

    @Test
    void preventsTwoProcessesFromSharingOneConfigIdentity() throws Exception {
        Path config = tempDir.resolve("local-executor.json");
        try (NodeProcessLock first = NodeProcessLock.acquire(config)) {
            IllegalStateException error = assertThrows(
                    IllegalStateException.class, () -> NodeProcessLock.acquire(config));
            assertTrue(error.getMessage().contains("local-executor.json.lock"));
            assertTrue(Files.exists(tempDir.resolve("local-executor.json.lock")));
        }
        try (NodeProcessLock afterRelease = NodeProcessLock.acquire(config)) {
            // The OS lock is released when the owning process closes it.
        }
    }
}
