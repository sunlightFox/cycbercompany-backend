package io.github.yourname.agentstudio.orchestration;

import io.github.yourname.agentstudio.security.ActorContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RunQueryService {

    private final AgentRunRepository runs;

    public RunQueryService(AgentRunRepository runs) {
        this.runs = runs;
    }

    @Transactional(readOnly = true)
    public RunView get(String runId, ActorContext actor) {
        // 运行 ID 不是访问凭证，查询时仍必须按当前租户过滤。
        return runs.findByIdAndTenantId(runId, actor.tenantId())
                .map(RunView::from)
                .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
    }
}
