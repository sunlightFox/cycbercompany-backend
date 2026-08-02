package io.github.yourname.agentstudio.orchestration;

import io.github.yourname.agentstudio.node.CodingRunEvidenceView;
import java.util.ArrayList;
import java.util.List;

/**
 * 服务端编码交付门禁。
 *
 * <p>模型的文字回答不能作为“代码已经可交付”的依据。这个类只使用服务端保存的节点工具审计摘要，
 * 在写入过项目文件时强制要求验证证据；节点客户端只负责上报执行结果，完全不参与通过或拒绝的判断。
 */
public final class CodingDeliveryGate {

    /** 交付门禁的结论。PASS 才允许运行进入 SUCCEEDED。 */
    public enum Status {
        PASS,
        NEEDS_VERIFICATION
    }

    /**
     * 审核结果中的原因可直接返回给前端和模型，但不得包含命令、路径或工具原始输出等敏感审计内容。
     */
    public record Decision(Status status, List<String> reasons) {

        public boolean passed() {
            return status == Status.PASS;
        }
    }

    /**
     * 对一次编码运行做最小、可解释的交付检查。
     *
     * <p>纯分析任务可能只读取代码，因此不要求测试命令。只要本次成功写入或打补丁，就必须至少有一次
     * 成功的 shell 验证；任何失败工具调用都会使本次交付停在待验证状态，避免用旧成功结果掩盖新失败。
     */
    public Decision evaluate(CodingRunEvidenceView evidence) {
        if (evidence == null) {
            return new Decision(Status.NEEDS_VERIFICATION, List.of("服务端没有找到本次编码运行的审计证据。"));
        }

        List<String> reasons = new ArrayList<>();
        boolean changedFiles = evidence.changedFiles() != null && !evidence.changedFiles().isEmpty();
        List<String> verificationTools = evidence.verificationTools() == null ? List.of() : evidence.verificationTools();
        List<String> commandVerifications = evidence.commandVerifications() == null ? List.of() : evidence.commandVerifications();
        List<String> failedTools = evidence.failedTools() == null ? List.of() : evidence.failedTools();
        List<String> traceArtifacts = evidence.browserTraceArtifacts() == null ? List.of() : evidence.browserTraceArtifacts();

        if (!failedTools.isEmpty()) {
            reasons.add("本次运行存在失败的节点工具调用，需先修复并重新验证。");
        }
        if (changedFiles && commandVerifications.isEmpty()) {
            reasons.add("已修改项目文件，但没有成功的构建、测试或命令验证证据。");
        }
        if (changedFiles && !evidence.gitReviewed()) {
            reasons.add("已修改项目文件，但最后一次修改后没有成功的 Git 变更审阅证据。");
        }
        if (changedFiles) {
            List<String> reviewedFiles = evidence.reviewedChangedFiles() == null ? List.of() : evidence.reviewedChangedFiles();
            if (!reviewedFiles.containsAll(evidence.changedFiles())) {
                reasons.add("已修改的项目文件尚未全部在最后一次修改后通过 git.diff 或 fs.read 审阅。");
            }
        }

        // 页面打开、点击、键盘输入、上传下载等都可能改变用户可见状态。Trace 让后续排错能复放
        // 关键过程，而 browser.verify 则断言最终页面事实；纯后端任务不会因为没有浏览器工具而被拦截。
        boolean browserInteraction = verificationTools.stream().anyMatch(CodingDeliveryGate::changesBrowserPageState);
        if (browserInteraction && traceArtifacts.isEmpty()) {
            reasons.add("已执行浏览器页面交互，但没有可回放的浏览器 Trace 证据。");
        }
        if (browserInteraction && !evidence.browserVerified()) {
            reasons.add("已执行浏览器页面交互，但最后一次交互后没有成功的 browser.verify 证据。");
        }
        if (failedTools.stream().anyMatch(tool -> tool.startsWith("browser.")) && !evidence.browserVerified()) {
            reasons.add("浏览器验证未成功完成。");
        }

        boolean desktopUiInteraction = evidence.succeededTools() != null
                && evidence.succeededTools().stream().anyMatch(CodingDeliveryGate::changesDesktopControl);
        if (desktopUiInteraction && !evidence.desktopUiVerified()) {
            reasons.add("已执行 Windows UI Automation 点击或输入，但最后一次操作后没有成功的 system.desktop.ui.verify 或受审批 read_value 证据。");
        }

        return reasons.isEmpty()
                ? new Decision(Status.PASS, List.of())
                : new Decision(Status.NEEDS_VERIFICATION, List.copyOf(reasons));
    }

    private static boolean changesBrowserPageState(String toolName) {
        return switch (toolName) {
            case "browser.open", "browser.switch_tab", "browser.close_tab", "browser.download", "browser.upload",
                    "browser.click", "browser.type", "browser.hover", "browser.press",
                    "browser.select_option" -> true;
            default -> false;
        };
    }

    private static boolean changesDesktopControl(String toolName) {
        return "system.desktop.ui.click".equals(toolName) || "system.desktop.ui.type".equals(toolName);
    }

    /** Applies task-specific evidence requirements after the general node-run checks. */
    public Decision evaluate(CodingRunEvidenceView evidence, NodeTaskPolicy taskPolicy) {
        Decision base = evaluate(evidence);
        if (taskPolicy == null
                || (!taskPolicy.requiresDesktopOrganizationEvidence() && !taskPolicy.requiresFullStackApiEvidence())) {
            return base;
        }

        List<String> reasons = new ArrayList<>(base.reasons());
        if (taskPolicy.requiresFullStackApiEvidence()
                && (evidence == null || !evidence.browserApiVerified())) {
            // 面向“前后端联调”的任务，页面文字、路由变化或旧网络记录都不足以说明请求成功。
            // Node 只在最后一次页面操作之后存在成功 responseStatus/responseUrlContains 断言时才给出该证据。
            reasons.add("前后端联调任务缺少最后一次页面操作后的成功 API 响应验证证据。");
        }
        if (!taskPolicy.requiresDesktopOrganizationEvidence()) {
            return reasons.isEmpty()
                    ? new Decision(Status.PASS, List.of())
                    : new Decision(Status.NEEDS_VERIFICATION, List.copyOf(reasons));
        }
        List<String> succeededTools = evidence == null || evidence.succeededTools() == null
                ? List.of()
                : evidence.succeededTools();
        if (!succeededTools.contains("system.desktop.organize.list")) {
            reasons.add("The desktop contents were not successfully inspected before completion.");
        }
        if (evidence == null) {
            reasons.add("Desktop execution evidence is unavailable for this task.");
        } else if (!succeededTools.contains("system.desktop.organize.write")
                && !succeededTools.contains("system.desktop.organize.move")
                && !succeededTools.contains("system.desktop.organize.delete")
                && evidence.desktopSortableFiles() != 0) {
            reasons.add("No successful desktop file creation, move, or deletion was recorded for this task.");
        }
        return reasons.isEmpty()
                ? new Decision(Status.PASS, List.of())
                : new Decision(Status.NEEDS_VERIFICATION, List.copyOf(reasons));
    }
}
