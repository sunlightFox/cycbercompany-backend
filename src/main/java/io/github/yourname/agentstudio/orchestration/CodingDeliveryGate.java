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

        // 浏览器只要执行过真实输入或点击，就属于可见行为验证。Trace 让后续排错能复放关键过程，
        // 但纯后端任务不会因为没有浏览器工具而被拦截。
        boolean browserInteraction = verificationTools.contains("browser.click") || verificationTools.contains("browser.type");
        if (browserInteraction && traceArtifacts.isEmpty()) {
            reasons.add("已执行浏览器输入或点击，但没有可回放的浏览器 Trace 证据。");
        }
        if (failedTools.stream().anyMatch(tool -> tool.startsWith("browser.")) && !evidence.browserVerified()) {
            reasons.add("浏览器验证未成功完成。");
        }

        return reasons.isEmpty()
                ? new Decision(Status.PASS, List.of())
                : new Decision(Status.NEEDS_VERIFICATION, List.copyOf(reasons));
    }
}
