package io.github.yourname.agentstudio.config;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Gives local H2 users an immediate, actionable duplicate-backend error.
 *
 * <p>This lock is intentionally independent of H2's lock. It prevents a second local JVM from
 * spending the connection-pool timeout competing for the same database file. Multi-instance
 * production deployments should use PostgreSQL and set {@code app.persistence.local-lock-enabled=false}.
 */
@Component
@ConditionalOnProperty(prefix = "app.persistence", name = "local-lock-enabled", havingValue = "true", matchIfMissing = true)
public final class LocalDataDirectoryLock {

    private final FileChannel channel;
    private final FileLock lock;

    public LocalDataDirectoryLock(@Value("${APP_DATA_DIR:./data}") String dataDirectory) {
        try {
            Path directory = Path.of(dataDirectory).toAbsolutePath().normalize();
            Files.createDirectories(directory);
            Path lockFile = directory.resolve(".agent-studio-backend.lock");
            channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = channel.tryLock();
            if (lock == null) {
                closeQuietly(channel);
                throw alreadyRunning(directory);
            }
        } catch (java.nio.channels.OverlappingFileLockException ex) {
            throw alreadyRunning(Path.of(dataDirectory).toAbsolutePath().normalize());
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot acquire local CycberCompany data lock.", ex);
        }
    }

    private static IllegalStateException alreadyRunning(Path directory) {
        return new IllegalStateException(
                "Another CycberCompany backend is already using local data directory " + directory
                        + ". Stop the existing backend or configure a distinct APP_DATA_DIR.");
    }

    @PreDestroy
    void release() {
        try {
            if (lock != null && lock.isValid()) {
                lock.release();
            }
        } catch (IOException ignored) {
        } finally {
            closeQuietly(channel);
        }
    }

    private static void closeQuietly(FileChannel value) {
        try {
            value.close();
        } catch (IOException ignored) {
        }
    }
}
