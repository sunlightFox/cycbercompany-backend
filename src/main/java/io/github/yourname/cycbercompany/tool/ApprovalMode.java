package io.github.yourname.cycbercompany.tool;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 一次 Run 选择的工具审批行为。
 *
 * <p>The mode changes whether a tool call pauses for a human decision. It never
 * disables the server-side invocation ledger: even {@link #FULL_ACCESS} calls
 * remain auditable and are still limited to tools exposed by the Run snapshot.
 */
public enum ApprovalMode {
    ON_REQUEST(
            "on-request",
            "请求批准",
            "编辑外部文件、运行高风险工具或访问受控资源前，始终请求批准。",
            true),
    AUTO_APPROVE(
            "auto-approve",
            "替我批准",
            "自动执行低、中风险工具；检测到高风险操作时请求批准。",
            true),
    FULL_ACCESS(
            "full-access",
            "完全访问权限",
            "不再为工具调用暂停请求批准；已启用工具、工作区范围和审计记录仍然生效。",
            false);

    private final String wireValue;
    private final String label;
    private final String description;
    private final boolean mayAskForApproval;

    ApprovalMode(String wireValue, String label, String description, boolean mayAskForApproval) {
        this.wireValue = wireValue;
        this.label = label;
        this.description = description;
        this.mayAskForApproval = mayAskForApproval;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    public boolean mayAskForApproval() {
        return mayAskForApproval;
    }

    /** Returns whether this mode can execute the supplied binding without a pause. */
    public boolean bypassesApproval(ResolvedToolBinding binding) {
        if (this == FULL_ACCESS) {
            return true;
        }
        if (this == AUTO_APPROVE) {
            return binding != null
                    && binding.riskLevel() != null
                    && binding.riskLevel().ordinal() < RiskLevel.HIGH.ordinal();
        }
        return false;
    }

    /** Returns whether a binding should create a pending approval in this mode. */
    public boolean requiresApproval(ResolvedToolBinding binding) {
        return binding != null && binding.requiresApproval() && !bypassesApproval(binding);
    }

    @JsonCreator
    public static ApprovalMode from(String value) {
        if (value == null || value.isBlank()) {
            return ON_REQUEST;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return Arrays.stream(values())
                .filter(mode -> mode.wireValue.equals(normalized)
                        || mode.name().toLowerCase(Locale.ROOT).equals(normalized)
                        || (mode == FULL_ACCESS && "full".equals(normalized))
                        || (mode == AUTO_APPROVE && "auto".equals(normalized)))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unsupported approval mode: " + value + ". Use on-request, auto-approve, or full-access."));
    }

    public static List<ApprovalModeOption> options() {
        return Arrays.stream(values())
                .map(mode -> new ApprovalModeOption(
                        mode.wireValue, mode.label, mode.description, mode.mayAskForApproval, true))
                .toList();
    }

    /** Stable metadata for a picker in the web client. */
    public record ApprovalModeOption(
            String id,
            String label,
            String description,
            boolean mayAskForApproval,
            boolean auditEnabled) {
    }
}
