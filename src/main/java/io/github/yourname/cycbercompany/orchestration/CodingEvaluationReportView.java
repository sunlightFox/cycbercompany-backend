package io.github.yourname.cycbercompany.orchestration;

import java.time.Instant;
import java.util.List;

/**
 * 一次编码评测的可安全公开报告。
 *
 * <p>报告只包含已经脱敏、汇总后的审计事实，例如工具类别、分数和文件数量；不返回源代码、
 * 命令参数、终端输出、浏览器响应正文或节点绝对路径。这样它既能放进 CI 产物，也不会把
 * 真实项目中的密钥或用户数据带出工作区。
 */
public record CodingEvaluationReportView(
        String runId,
        String scenario,
        String scenarioLabel,
        RunStatus runStatus,
        Instant startedAt,
        Instant finishedAt,
        int score,
        boolean passed,
        List<CodingEvaluationCheckView> checks,
        List<String> recommendations) {

    /** 单项评分必须列出获得分、满分和服务端观察到的简短证据。 */
    public record CodingEvaluationCheckView(
            String category,
            int earnedPoints,
            int maximumPoints,
            boolean passed,
            String evidence) {
    }
}
