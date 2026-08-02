package io.github.yourname.agentstudio.node;

import java.util.List;

/**
 * 编码任务的安全交付摘要。
 *
 * <p>这是给页面或 API 使用的只读视图，不包含源码、命令参数、命令输出或报错详情。
 * 这些原始数据可能含有密钥、目录结构或用户输入；需要排查时应通过受权限保护的调用记录查看。
 */
public record CodingRunEvidenceView(
        /** 编码任务的唯一标识。 */
        String runId,
        /** 本次任务记录到的全部节点工具调用次数，包含失败调用。 */
        int toolCalls,
        List<String> succeededTools,
        /** Number of visible, top-level regular files observed by the scoped desktop inspection; -1 if unavailable. */
        int desktopSortableFiles,
        /** 成功写入或打补丁的项目内文件路径，已去重。 */
        List<String> changedFiles,
        /** 最后一次项目写入后是否成功执行过 git.review。 */
        boolean gitReviewed,
        /** 最后一次项目写入后，已通过 fs.read 或 git.diff 审阅的变更文件路径。 */
        List<String> reviewedChangedFiles,
        /** 成功执行过的验证工具类别，例如 shell.run、browser.open。 */
        List<String> verificationTools,
        /**
         * 服务端从成功 shell.run 的审计参数中归类出的验证类别，例如 test、build、lint、typecheck 或 http。
         * 不返回原始命令，避免把令牌、目录或其他敏感参数泄露到交付摘要接口。
         */
        List<String> commandVerifications,
        /** 成功导出的浏览器 Trace 文件名，不包含节点机器上的绝对路径。 */
        List<String> browserTraceArtifacts,
        /** 最后一次会改变页面状态的浏览器操作后，是否成功执行过 browser.verify。 */
        boolean browserVerified,
        /** 最后一次 Windows UI Automation 点击或输入后，是否成功复核控件或读取过其非密码值。 */
        boolean desktopUiVerified,
        /** 失败的工具类别，帮助快速判断验证链路是否中断。 */
        List<String> failedTools,
        /**
         * 最后一次浏览器页面交互后，是否有成功的 API 响应断言。
         *
         * <p>普通可见文本或 URL 断言足以证明网页流程；但前后端联调任务还必须证明真实响应状态或路径检查通过。
         */
        boolean browserApiVerified) {

    /** 保留旧的完整构造形状，旧调用方不能把页面验证误认成 API 验证。 */
    public CodingRunEvidenceView(
            String runId,
            int toolCalls,
            List<String> succeededTools,
            int desktopSortableFiles,
            List<String> changedFiles,
            boolean gitReviewed,
            List<String> reviewedChangedFiles,
            List<String> verificationTools,
            List<String> commandVerifications,
            List<String> browserTraceArtifacts,
            boolean browserVerified,
            boolean desktopUiVerified,
            List<String> failedTools) {
        this(
                runId,
                toolCalls,
                succeededTools,
                desktopSortableFiles,
                changedFiles,
                gitReviewed,
                reviewedChangedFiles,
                verificationTools,
                commandVerifications,
                browserTraceArtifacts,
                browserVerified,
                desktopUiVerified,
                failedTools,
                false);
    }

    /** Keeps the constructor shape used before desktop UI verification evidence was added. */
    public CodingRunEvidenceView(
            String runId,
            int toolCalls,
            List<String> succeededTools,
            int desktopSortableFiles,
            List<String> changedFiles,
            boolean gitReviewed,
            List<String> reviewedChangedFiles,
            List<String> verificationTools,
            List<String> commandVerifications,
            List<String> browserTraceArtifacts,
            boolean browserVerified,
            List<String> failedTools) {
        this(
                runId,
                toolCalls,
                succeededTools,
                desktopSortableFiles,
                changedFiles,
                gitReviewed,
                reviewedChangedFiles,
                verificationTools,
                commandVerifications,
                browserTraceArtifacts,
                browserVerified,
                false,
                failedTools);
    }

    /** Keeps the prior constructor shape for callers compiled against the original evidence view. */
    public CodingRunEvidenceView(
            String runId,
            int toolCalls,
            List<String> succeededTools,
            int desktopSortableFiles,
            List<String> changedFiles,
            List<String> verificationTools,
            List<String> commandVerifications,
            List<String> browserTraceArtifacts,
            boolean browserVerified,
            List<String> failedTools) {
        this(
                runId,
                toolCalls,
                succeededTools,
                desktopSortableFiles,
                changedFiles,
                false,
                List.of(),
                verificationTools,
                commandVerifications,
                browserTraceArtifacts,
                browserVerified,
                false,
                failedTools);
    }

    /** Keeps the prior constructor shape for callers compiled against the original evidence view. */
    public CodingRunEvidenceView(
            String runId,
            int toolCalls,
            List<String> succeededTools,
            List<String> changedFiles,
            List<String> verificationTools,
            List<String> commandVerifications,
            List<String> browserTraceArtifacts,
            boolean browserVerified,
            List<String> failedTools) {
        this(
                runId,
                toolCalls,
                succeededTools,
                -1,
                changedFiles,
                false,
                List.of(),
                verificationTools,
                commandVerifications,
                browserTraceArtifacts,
                browserVerified,
                false,
                failedTools);
    }

    public CodingRunEvidenceView(
            String runId,
            int toolCalls,
            List<String> changedFiles,
            List<String> verificationTools,
            List<String> commandVerifications,
            List<String> browserTraceArtifacts,
            boolean browserVerified,
            List<String> failedTools) {
        this(
                runId,
                toolCalls,
                List.of(),
                -1,
                changedFiles,
                false,
                List.of(),
                verificationTools,
                commandVerifications,
                browserTraceArtifacts,
                browserVerified,
                failedTools);
    }
}
