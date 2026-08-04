package io.github.yourname.agentstudio.nodeclient.config;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Prevents two node clients from using the same persisted identity concurrently. */
public final class NodeProcessLock implements AutoCloseable {

    private final FileChannel channel;
    private final FileLock lock;
    private final Path lockPath;

    private NodeProcessLock(FileChannel channel, FileLock lock, Path lockPath) {
        this.channel = channel;
        this.lock = lock;
        this.lockPath = lockPath;
    }

    public static NodeProcessLock acquire(Path configPath) throws IOException {
        if (configPath == null) {
            throw new IllegalArgumentException("Node config path is required.");
        }
        Path normalized = configPath.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("Node config path must have a parent directory.");
        }
        Files.createDirectories(parent);
        Path lockPath = normalized.resolveSibling(normalized.getFileName() + ".lock");
        FileChannel channel = FileChannel.open(
                lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                throw alreadyRunning(lockPath);
            }
            return new NodeProcessLock(channel, lock, lockPath);
        } catch (OverlappingFileLockException ex) {
            closeQuietly(channel);
            throw alreadyRunning(lockPath);
        } catch (IOException | RuntimeException ex) {
            closeQuietly(channel);
            throw ex;
        }
    }

    private static IllegalStateException alreadyRunning(Path lockPath) {
        return new IllegalStateException(
                "Another Agent Studio node process is already running for this config: " + lockPath);
    }

    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }

    private static void closeQuietly(FileChannel channel) {
        try {
            channel.close();
        } catch (IOException ignored) {
            // Preserve the original lock acquisition failure.
        }
    }
}
