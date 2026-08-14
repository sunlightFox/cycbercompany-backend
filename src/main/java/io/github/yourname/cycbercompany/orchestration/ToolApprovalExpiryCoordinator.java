package io.github.yourname.cycbercompany.orchestration;

import io.github.yourname.cycbercompany.tool.ToolApprovalService;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Keeps an abandoned approval from leaving its conversation queue permanently occupied. */
@Component
class ToolApprovalExpiryCoordinator {

    private final ToolApprovalService approvals;
    private final RunCommandService runs;

    ToolApprovalExpiryCoordinator(ToolApprovalService approvals, @Lazy RunCommandService runs) {
        this.approvals = approvals;
        this.runs = runs;
    }

    @Scheduled(fixedDelayString = "${app.tool-approval.expiry-poll-ms:15000}")
    void expireOutstandingApprovals() {
        approvals.expireOutstanding().forEach(approval ->
                runs.failExpiredToolApproval(approval.runId(), approval.tenantId(), approval.approvalId()));
    }
}
