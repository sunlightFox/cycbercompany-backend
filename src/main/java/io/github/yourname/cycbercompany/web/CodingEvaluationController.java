package io.github.yourname.cycbercompany.web;

import io.github.yourname.cycbercompany.orchestration.CodingEvaluationReportView;
import io.github.yourname.cycbercompany.orchestration.CodingEvaluationScenario;
import io.github.yourname.cycbercompany.orchestration.CodingEvaluationService;
import io.github.yourname.cycbercompany.orchestration.RunQueryService;
import io.github.yourname.cycbercompany.security.CurrentActorProvider;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 面向本地测试脚本和前端的编码评测只读入口。
 *
 * <p>评测必须显式指定场景，避免将“修复一个失败测试”的 Run 按全栈应用标准误判。
 * 控制器先以当前租户读取 Run，再调用服务生成报告，双层校验使猜测 Run ID 不能读取
 * 其他租户的评测信息。
 */
@RestController
@RequestMapping("/api/v1/runs")
class CodingEvaluationController {

    private final CurrentActorProvider actors;
    private final RunQueryService runQueries;
    private final CodingEvaluationService evaluations;

    CodingEvaluationController(
            CurrentActorProvider actors,
            RunQueryService runQueries,
            CodingEvaluationService evaluations) {
        this.actors = actors;
        this.runQueries = runQueries;
        this.evaluations = evaluations;
    }

    @GetMapping("/{id}/coding-evaluation")
    CodingEvaluationReportView evaluate(
            @PathVariable String id,
            @RequestParam String scenario,
            HttpServletRequest request) {
        var actor = actors.current(request);
        // 先执行现有的租户范围校验，评测服务内部也会以 tenantId 再读取一次原始审计记录。
        runQueries.get(id, actor);
        return evaluations.evaluate(id, CodingEvaluationScenario.from(scenario), actor);
    }
}
