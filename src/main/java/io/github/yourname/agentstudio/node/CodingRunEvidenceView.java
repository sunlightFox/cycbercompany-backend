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
        boolean browserApiVerified,
        /**
         * 是否观察到所有成功启动的桌面应用窗口。启动进程本身不代表窗口已经出现，
         * 必须在后续 session.snapshot 中看到相同的 processId 才算通过。
         */
        boolean desktopApplicationVerified,
        /**
         * 本次 Run 启动的受管本地进程是否已通过同一进程句柄的 loopback HTTP 就绪探测。
         * 这比单独的 process.start 或浏览器页面断言更强，适用于前后端联调交付。
         */
        boolean managedProcessReady,
        /**
         * 最后一次项目文件修改之后，是否重新启动并通过受管 loopback HTTP 就绪探测。
         *
         * <p>旧进程在修改前的健康检查不能证明新代码已经被加载，因此全栈评测必须使用此字段，
         * 而不能只使用 {@link #managedProcessReady()}。
         */
        boolean managedProcessReadyAfterLastProjectChange) {

    /**
     * 保留引入“修改后就绪”前的完整构造形状。旧调用方没有时序证据，默认不得被当成新代码已联调。
     */
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
            List<String> failedTools,
            boolean browserApiVerified,
            boolean desktopApplicationVerified,
            boolean managedProcessReady) {
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
                browserApiVerified,
                desktopApplicationVerified,
                managedProcessReady,
                false);
    }

    /**
     * 保留桌面应用窗口证据引入后的构造函数形状。旧调用方不能因为没有受管服务证据而误判为已就绪。
     */
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
            List<String> failedTools,
            boolean browserApiVerified,
            boolean desktopApplicationVerified) {
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
                browserApiVerified,
                desktopApplicationVerified,
                false);
    }

    /** 保留当前版本的完整构造函数，旧调用方默认没有桌面应用启动证据。 */
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
            List<String> failedTools,
            boolean browserApiVerified) {
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
                browserApiVerified,
                false,
                false);
    }

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
