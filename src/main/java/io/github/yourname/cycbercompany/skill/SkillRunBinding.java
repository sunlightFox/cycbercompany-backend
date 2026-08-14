package io.github.yourname.cycbercompany.skill;

/**
 * 一次 Run 锁定的 Skill Release。
 *
 * <p>这里只保存可公开审计的来源和摘要，不保存服务端绝对路径。运行时根据 skillId + digest
 * 从不可变 Release Store 读取指令，因此当前安装目录被升级或删除也不会改变旧 Run 的语义。
 */
public record SkillRunBinding(
        String skillId,
        String name,
        String description,
        String digest,
        String sourceRepository,
        String sourceUrl,
        String requestedRef,
        String resolvedCommit,
        String sourcePath) {
}
