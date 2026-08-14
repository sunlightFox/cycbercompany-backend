package io.github.yourname.cycbercompany.orchestration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 可持久化的编码工作流计划。
 *
 * <p>该对象故意只识别工具的类别，不记录调用参数或原始结果。它既可供 API 展示，也可在审批恢复、
 * 服务重启后的下一轮模型调用前生成可靠摘要。任何未知工具都不会虚假推进步骤。
 */
public record CodingWorkflowPlan(
        int schemaVersion,
        boolean projectFilesChanged,
        List<CodingWorkflowStepState> steps) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public CodingWorkflowPlan {
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException("Coding workflow plan schemaVersion must be positive.");
        }
        Map<CodingWorkflowStep, CodingWorkflowStepState> unique = new EnumMap<>(CodingWorkflowStep.class);
        for (CodingWorkflowStepState state : steps == null ? List.<CodingWorkflowStepState>of() : steps) {
            if (state == null || unique.putIfAbsent(state.step(), state) != null) {
                throw new IllegalArgumentException("Coding workflow plan must contain each step at most once.");
            }
        }
        List<CodingWorkflowStepState> ordered = new ArrayList<>();
        for (CodingWorkflowStep step : CodingWorkflowStep.values()) {
            ordered.add(unique.getOrDefault(step, CodingWorkflowStepState.pending(step)));
        }
        steps = List.copyOf(ordered);
    }

    public static CodingWorkflowPlan initial() {
        return new CodingWorkflowPlan(CURRENT_SCHEMA_VERSION, false, List.of());
    }

    /** 根据一次已完成的工具调用更新步骤，不保存具体工具参数或错误文本。 */
    public CodingWorkflowPlan afterToolResult(String toolName, boolean succeeded, Instant now) {
        String normalizedToolName = ToolCategory.normalize(toolName);
        ToolCategory category = ToolCategory.from(normalizedToolName);
        if (category == ToolCategory.OTHER) {
            return this;
        }
        if (!succeeded) {
            return replace(failureTarget(category), state(failureTarget(category)).blocked(
                    "该步骤的工具调用失败，需在恢复后重新检查。", now));
        }

        return switch (category) {
            case INSPECTION -> afterInspection(normalizedToolName, now);
            case CHANGE -> afterChange(now);
            case VERIFICATION -> afterVerification(now);
            case REVIEW -> afterReview(now);
            case OTHER -> this;
        };
    }

    /**
     * 以服务端交付证据收尾计划。
     *
     * <p>节点审计发现的文件变更比工具名称更可信，例如 shell.run 可能间接写入 lock 文件。因此此处会
     * 校正 projectFilesChanged，避免把没有结构化修改记录的变更错误放行。
     */
    public CodingWorkflowPlan afterDeliveryEvidence(boolean evidenceShowsChanges, boolean deliveryGatePassed, Instant now) {
        CodingWorkflowPlan plan = this;
        if (evidenceShowsChanges && !plan.projectFilesChanged) {
            plan = plan.withProjectFilesChanged(true)
                    .replace(CodingWorkflowStep.IMPLEMENT, plan.state(CodingWorkflowStep.IMPLEMENT).blocked(
                            "发现项目文件变更，但未记录到可识别的修改步骤。", now));
        }
        if (!deliveryGatePassed) {
            return plan.replace(CodingWorkflowStep.DELIVER, plan.state(CodingWorkflowStep.DELIVER).blocked(
                    "服务端交付门禁尚未通过。", now));
        }
        if (!plan.projectFilesChanged) {
            plan = plan.replace(CodingWorkflowStep.PLAN, plan.state(CodingWorkflowStep.PLAN).notRequired(now))
                    .replace(CodingWorkflowStep.IMPLEMENT, plan.state(CodingWorkflowStep.IMPLEMENT).notRequired(now))
                    .replace(CodingWorkflowStep.VERIFY, plan.state(CodingWorkflowStep.VERIFY).notRequired(now))
                    .replace(CodingWorkflowStep.REVIEW, plan.state(CodingWorkflowStep.REVIEW).notRequired(now));
        }
        if (!plan.deliveryBlockers().isEmpty()) {
            return plan.replace(CodingWorkflowStep.DELIVER, plan.state(CodingWorkflowStep.DELIVER).blocked(
                    "交付证据与编码工作流步骤不一致。", now));
        }
        return plan.replace(CodingWorkflowStep.DELIVER, plan.state(CodingWorkflowStep.DELIVER).completed(now));
    }

    /** 返回不泄露命令、路径和原始输出的交付缺项说明。 */
    public List<String> deliveryBlockers() {
        List<CodingWorkflowStep> required = projectFilesChanged
                ? List.of(CodingWorkflowStep.INSPECT, CodingWorkflowStep.PLAN, CodingWorkflowStep.IMPLEMENT,
                        CodingWorkflowStep.VERIFY, CodingWorkflowStep.REVIEW)
                : List.of(CodingWorkflowStep.INSPECT);
        List<String> blockers = new ArrayList<>();
        for (CodingWorkflowStep step : required) {
            if (state(step).status() != CodingWorkflowStepStatus.COMPLETED) {
                blockers.add("编码工作流步骤未完成：" + step.wireValue() + "。需要："
                        + String.join("、", step.requiredEvidence()) + "。");
            }
        }
        return List.copyOf(blockers);
    }

    /** 给下一轮模型的宿主摘要，只包含步骤状态和需补充的证据类别。 */
    public String resumeGuidance() {
        StringBuilder guidance = new StringBuilder("Host workflow checkpoint (authoritative, no raw tool output):\n");
        for (CodingWorkflowStepState state : steps) {
            guidance.append("- ").append(state.step().wireValue()).append(": ")
                    .append(state.status().wireValue());
            if (state.status() != CodingWorkflowStepStatus.COMPLETED
                    && state.status() != CodingWorkflowStepStatus.NOT_REQUIRED) {
                guidance.append("; required evidence: ")
                        .append(String.join(", ", state.step().requiredEvidence()));
            }
            if (state.failureSummary() != null) {
                guidance.append("; recovery: ").append(state.failureSummary());
            }
            guidance.append('\n');
        }
        guidance.append("Do not claim delivery until the required steps have server-side evidence.");
        return guidance.toString();
    }

    private CodingWorkflowPlan afterInspection(String toolName, Instant now) {
        // 修改后的 fs.read 是交付门禁认可的文件审阅证据；其他项目检查仍只推进 inspect。
        if (projectFilesChanged && "fs.read".equalsIgnoreCase(toolName)) {
            CodingWorkflowPlan plan = replace(CodingWorkflowStep.INSPECT, state(CodingWorkflowStep.INSPECT).completed(now));
            return plan.replace(CodingWorkflowStep.REVIEW, plan.state(CodingWorkflowStep.REVIEW).completed(now));
        }
        CodingWorkflowPlan plan = replace(CodingWorkflowStep.INSPECT, state(CodingWorkflowStep.INSPECT).completed(now));
        return plan.replace(CodingWorkflowStep.PLAN, plan.state(CodingWorkflowStep.PLAN).inProgress(now));
    }

    private CodingWorkflowPlan afterChange(Instant now) {
        CodingWorkflowPlan plan = withProjectFilesChanged(true);
        if (plan.state(CodingWorkflowStep.INSPECT).status() != CodingWorkflowStepStatus.COMPLETED) {
            plan = plan.replace(CodingWorkflowStep.INSPECT, plan.state(CodingWorkflowStep.INSPECT).blocked(
                    "修改前未记录到项目检查，恢复后需先补充检查。", now));
        }
        plan = plan.replace(CodingWorkflowStep.PLAN, plan.state(CodingWorkflowStep.PLAN).completed(now))
                .replace(CodingWorkflowStep.IMPLEMENT, plan.state(CodingWorkflowStep.IMPLEMENT).completed(now));
        // 新的文件变更会让较早的测试和审阅证据过期，必须在这次修改后重新获得证据。
        return plan.replace(CodingWorkflowStep.VERIFY, CodingWorkflowStepState.pending(CodingWorkflowStep.VERIFY))
                .replace(CodingWorkflowStep.REVIEW, CodingWorkflowStepState.pending(CodingWorkflowStep.REVIEW));
    }

    private CodingWorkflowPlan afterVerification(Instant now) {
        if (!projectFilesChanged) {
            return this;
        }
        return replace(CodingWorkflowStep.VERIFY, state(CodingWorkflowStep.VERIFY).completed(now));
    }

    private CodingWorkflowPlan afterReview(Instant now) {
        if (!projectFilesChanged) {
            return afterInspection("git.diff", now);
        }
        return replace(CodingWorkflowStep.REVIEW, state(CodingWorkflowStep.REVIEW).completed(now));
    }

    private CodingWorkflowStep failureTarget(ToolCategory category) {
        return switch (category) {
            case INSPECTION -> projectFilesChanged ? CodingWorkflowStep.REVIEW : CodingWorkflowStep.INSPECT;
            case CHANGE -> CodingWorkflowStep.IMPLEMENT;
            case VERIFICATION -> CodingWorkflowStep.VERIFY;
            case REVIEW -> CodingWorkflowStep.REVIEW;
            case OTHER -> CodingWorkflowStep.INSPECT;
        };
    }

    /** 返回某一步的安全状态，供恢复 UI 和交付门禁读取。 */
    public CodingWorkflowStepState state(CodingWorkflowStep step) {
        return steps.stream().filter(item -> item.step() == step).findFirst().orElseThrow();
    }

    private CodingWorkflowPlan replace(CodingWorkflowStep step, CodingWorkflowStepState replacement) {
        return new CodingWorkflowPlan(schemaVersion, projectFilesChanged, steps.stream()
                .map(item -> item.step() == step ? replacement : item)
                .toList());
    }

    private CodingWorkflowPlan withProjectFilesChanged(boolean changed) {
        return new CodingWorkflowPlan(schemaVersion, changed, steps);
    }

    private enum ToolCategory {
        INSPECTION,
        CHANGE,
        VERIFICATION,
        REVIEW,
        OTHER;

        static ToolCategory from(String toolName) {
            String name = normalize(toolName);
            if (name.equals("fs.write") || name.equals("fs.apply_patch") || name.equals("fs.apply_patch_batch")) {
                return CHANGE;
            }
            if (name.equals("shell.run") || name.equals("process.wait_http") || name.equals("browser.verify")
                    || name.equals("browser.wait_response") || name.equals("desktop.ui.verify")
                    || name.equals("desktop.ui.read_value")) {
                return VERIFICATION;
            }
            if (name.equals("git.review") || name.equals("git.diff")) {
                return REVIEW;
            }
            if (name.startsWith("project.") || name.equals("fs.list") || name.equals("fs.read")
                    || name.equals("fs.search") || name.equals("git.status")) {
                return INSPECTION;
            }
            return OTHER;
        }

        static String normalize(String toolName) {
            String name = toolName == null ? "" : toolName.toLowerCase(Locale.ROOT);
            if (name.startsWith("system.")) {
                name = name.substring("system.".length());
            }
            return name;
        }
    }
}
