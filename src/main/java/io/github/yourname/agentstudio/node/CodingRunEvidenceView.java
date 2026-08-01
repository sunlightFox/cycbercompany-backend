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
        /** 成功写入或打补丁的项目内文件路径，已去重。 */
        List<String> changedFiles,
        /** 成功执行过的验证工具类别，例如 shell.run、browser.open。 */
        List<String> verificationTools,
        /**
         * 服务端从成功 shell.run 的审计参数中归类出的验证类别，例如 test、build、lint、typecheck 或 http。
         * 不返回原始命令，避免把令牌、目录或其他敏感参数泄露到交付摘要接口。
         */
        List<String> commandVerifications,
        /** 成功导出的浏览器 Trace 文件名，不包含节点机器上的绝对路径。 */
        List<String> browserTraceArtifacts,
        /** 是否至少执行过一次成功的浏览器验证工具。 */
        boolean browserVerified,
        /** 失败的工具类别，帮助快速判断验证链路是否中断。 */
        List<String> failedTools) {
}
