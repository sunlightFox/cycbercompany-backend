package io.github.yourname.agentstudio.skill;

/** 从不可变 Skill Release 读取的有界文本资源。 */
public record SkillResourceContent(
        String skillId,
        String releaseDigest,
        String path,
        String content,
        boolean truncated) {
}
