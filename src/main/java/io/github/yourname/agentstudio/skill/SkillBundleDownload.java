package io.github.yourname.agentstudio.skill;

import java.nio.file.Path;

/**
 * 已准备好供节点下载的不可变 Skill Bundle。
 *
 * <p>{@code releaseDigest} 校验解压后的目录树，{@code bundleDigest} 校验下载到的 ZIP 字节。
 * 两个摘要用途不同，节点必须先校验 ZIP，再解压并复核目录树，不能只做其中一步。
 */
public record SkillBundleDownload(
        String skillId,
        String releaseDigest,
        String bundleDigest,
        long sizeBytes,
        Path path) {

    public SkillBundleDownload {
        if (skillId == null || skillId.isBlank()) {
            throw new IllegalArgumentException("Skill ID must not be blank.");
        }
        if (releaseDigest == null || releaseDigest.isBlank()) {
            throw new IllegalArgumentException("Skill release digest must not be blank.");
        }
        if (bundleDigest == null || bundleDigest.isBlank()) {
            throw new IllegalArgumentException("Skill bundle digest must not be blank.");
        }
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("Skill bundle size must not be negative.");
        }
        if (path == null || !path.isAbsolute()) {
            throw new IllegalArgumentException("Skill bundle path must be absolute.");
        }
    }
}
