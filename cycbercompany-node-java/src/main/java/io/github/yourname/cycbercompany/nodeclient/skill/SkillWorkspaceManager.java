package io.github.yourname.cycbercompany.nodeclient.skill;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.UUID;

/** 为每个 Run 创建与只读缓存隔离的可写 Skill 工作副本。 */
public final class SkillWorkspaceManager {

    private final Path workspaceRoot;

    public SkillWorkspaceManager(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
    }

    public synchronized Path materialize(String runId, CachedSkillBundle bundle) {
        String safeRun = safeName(runId, "runId");
        String releaseHex = bundle.releaseDigest().substring("sha256:".length());
        Path runRoot = workspaceRoot.resolve(safeRun).normalize();
        ensureInside(workspaceRoot, runRoot);
        Path target = runRoot.resolve(bundle.skillId() + "-" + releaseHex).normalize();
        ensureInside(runRoot, target);
        try {
            if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                return target;
            }
            Files.createDirectories(runRoot);
            Path staging = runRoot.resolve("." + target.getFileName() + "-" + UUID.randomUUID());
            try {
                copyTree(bundle.contentRoot(), staging);
                Files.move(staging, target);
            } finally {
                deleteDirectoryIfExists(staging);
            }
            return target;
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to materialize Skill workspace: " + ex.getMessage(), ex);
        }
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (var stream = Files.walk(source)) {
            for (Path input : stream.sorted().toList()) {
                if (Files.isSymbolicLink(input)) {
                    throw new IOException("Skill cache contains a symbolic link: " + input);
                }
                Path relative = source.relativize(input);
                Path output = target.resolve(relative).normalize();
                ensureInside(target, output);
                if (Files.isDirectory(input, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(output);
                    output.toFile().setWritable(true);
                } else if (Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(output.getParent());
                    Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                    output.toFile().setWritable(true);
                }
            }
        }
    }

    private static void deleteDirectoryIfExists(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                path.toFile().setWritable(true);
                Files.deleteIfExists(path);
            }
        }
    }

    private static String safeName(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank.");
        String safe = value.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.length() <= 120 ? safe : safe.substring(0, 120);
    }

    private static void ensureInside(Path root, Path child) {
        if (!child.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("Resolved Skill workspace escaped its managed root.");
        }
    }
}
