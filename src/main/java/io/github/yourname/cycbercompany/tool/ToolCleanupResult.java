package io.github.yourname.cycbercompany.tool;

/** Run 结束时由 Provider 回收本地会话或受管进程的结果。 */
public record ToolCleanupResult(
        String providerId,
        String targetId,
        String toolName,
        boolean succeeded,
        String errorMessage) {
}
