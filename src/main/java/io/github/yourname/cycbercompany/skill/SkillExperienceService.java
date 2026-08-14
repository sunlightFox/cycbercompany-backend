package io.github.yourname.cycbercompany.skill;

import io.github.yourname.cycbercompany.agent.AgentCatalog;
import io.github.yourname.cycbercompany.knowledge.KnowledgeQueryService;
import io.github.yourname.cycbercompany.node.NodeDetailView;
import io.github.yourname.cycbercompany.node.NodeService;
import io.github.yourname.cycbercompany.security.ActorContext;
import io.github.yourname.cycbercompany.tool.ResolvedToolBinding;
import io.github.yourname.cycbercompany.tool.ToolDiscoveryRequest;
import io.github.yourname.cycbercompany.tool.ToolRouter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Builds UI-ready Skill readiness and static test reports without creating a Run. */
@Service
public class SkillExperienceService {

    private static final String DEFAULT_AGENT_ID = "default-assistant";

    private final SkillCatalog skills;
    private final SkillAnalyzer analyzer;
    private final SkillCompatibilityService compatibility;
    private final AgentCatalog agents;
    private final KnowledgeQueryService knowledge;
    private final NodeService nodes;
    private final ToolRouter tools;

    public SkillExperienceService(
            SkillCatalog skills,
            SkillAnalyzer analyzer,
            SkillCompatibilityService compatibility,
            AgentCatalog agents,
            KnowledgeQueryService knowledge,
            NodeService nodes,
            ToolRouter tools) {
        this.skills = skills;
        this.analyzer = analyzer;
        this.compatibility = compatibility;
        this.agents = agents;
        this.knowledge = knowledge;
        this.nodes = nodes;
        this.tools = tools;
    }

    public SkillPreflightView preflight(SkillPreflightCommand command, ActorContext actor) {
        SkillPreflightCommand request = command == null
                ? new SkillPreflightCommand(List.of(), null, null, List.of(), List.of(), List.of())
                : command;
        String agentId = blankToDefault(request.agentId(), DEFAULT_AGENT_ID);
        var agent = agents.get(agentId);
        if (!agent.enabled()) {
            throw new IllegalArgumentException("Agent is disabled: " + agentId);
        }
        nodes.validateExecutionTarget(request.nodeId(), actor);
        List<SkillRunBinding> bindings = skills.resolveForRun(request.skillIds());
        List<String> knowledgeBaseIds = knowledge.resolveKnowledgeBaseIds(request.knowledgeBaseIds(), actor);
        List<ResolvedToolBinding> effectiveTools = tools.resolve(
                new ToolDiscoveryRequest(
                        "preflight_" + UUID.randomUUID(),
                        request.nodeId(),
                        knowledgeBaseIds,
                        request.mcpServerIds(),
                        bindings,
                        actor),
                request.toolNames(),
                agent.toolAllowList());
        List<SkillAnalysis> analyses = analyzer.analyze(bindings);
        NodeDetailView node = request.nodeId() == null || request.nodeId().isBlank()
                ? null
                : nodes.get(request.nodeId(), actor);
        CompatibilityReport report = compatibility.check(analyses, effectiveTools, node);
        return new SkillPreflightView(
                report.compatible(), agentId, request.nodeId(), bindings, analyses, report, effectiveTools);
    }

    public SkillTestView test(SkillPreflightCommand command, ActorContext actor) {
        SkillPreflightView preflight = preflight(command, actor);
        List<SkillTestView.Check> checks = new ArrayList<>();
        checks.add(new SkillTestView.Check(
                "release_snapshot",
                "PASSED",
                "Selected Skill releases are immutable and digest-verified."));
        checks.add(new SkillTestView.Check(
                "static_analysis",
                "PASSED",
                "Instructions, resources, scripts, and declared requirements were parsed without executing code."));
        checks.add(new SkillTestView.Check(
                "capability_compatibility",
                preflight.ready() ? "PASSED" : "FAILED",
                preflight.ready()
                        ? "The selected Agent, tools, and node satisfy the Skill requirements."
                        : "Review the structured compatibility issues before starting a Run."));
        checks.add(new SkillTestView.Check(
                "script_execution",
                "NOT_RUN",
                "Scripts are not executed by static testing. Run them only through an approved Run on a compatible node."));
        return new SkillTestView(preflight, checks);
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
