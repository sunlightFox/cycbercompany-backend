package io.github.yourname.agentstudio.nodeclient.skill;

import java.nio.file.Path;

/** 节点本地已经完成 ZIP 与目录树双重校验的 Skill 缓存。 */
public record CachedSkillBundle(
        String skillId,
        String releaseDigest,
        String bundleDigest,
        Path contentRoot) {
}
