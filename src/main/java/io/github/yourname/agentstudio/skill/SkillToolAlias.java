package io.github.yourname.agentstudio.skill;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** 市场 Skill 常见工具名到平台逻辑工具名的兼容别名。 */
public final class SkillToolAlias {

    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("read", "fs.read"),
            Map.entry("write", "fs.write"),
            Map.entry("edit", "fs.apply_patch"),
            Map.entry("multiedit", "fs.apply_patch"),
            Map.entry("glob", "fs.search"),
            Map.entry("grep", "fs.search"),
            Map.entry("bash", "shell.run"),
            Map.entry("shell", "shell.run"),
            Map.entry("websearch", "web_search"),
            Map.entry("webfetch", "web_search"));

    private SkillToolAlias() {
    }

    public static Optional<String> resolve(String declaredName) {
        if (declaredName == null || declaredName.isBlank()) {
            return Optional.empty();
        }
        String trimmed = declaredName.trim();
        int scopedArguments = trimmed.indexOf('(');
        if (scopedArguments > 0 && trimmed.endsWith(")")) {
            // Claude Code 常用 Bash(git:*), Read(./src/**) 表达更窄的使用范围。
            // 当前阶段只解析基础工具名；括号里的范围不会转化成额外授权。
            trimmed = trimmed.substring(0, scopedArguments);
        }
        String normalized = trimmed.toLowerCase(Locale.ROOT);
        if (normalized.matches("[a-z0-9_.:-]+")) {
            return Optional.of(ALIASES.getOrDefault(normalized, normalized));
        }
        return Optional.ofNullable(ALIASES.get(normalized.replaceAll("[^a-z0-9]", "")));
    }
}
