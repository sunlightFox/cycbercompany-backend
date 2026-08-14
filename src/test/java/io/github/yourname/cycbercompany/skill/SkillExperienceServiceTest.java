package io.github.yourname.cycbercompany.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.yourname.cycbercompany.agent.AgentCatalog;
import io.github.yourname.cycbercompany.agent.AgentDefinitionView;
import io.github.yourname.cycbercompany.knowledge.KnowledgeQueryService;
import io.github.yourname.cycbercompany.node.NodeService;
import io.github.yourname.cycbercompany.security.ActorContext;
import io.github.yourname.cycbercompany.tool.ToolRouter;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SkillExperienceServiceTest {

    @Test
    void returnsReadinessWithoutCreatingOrQueuingARun() {
        SkillCatalog skills = mock(SkillCatalog.class);
        SkillAnalyzer analyzer = mock(SkillAnalyzer.class);
        SkillCompatibilityService compatibility = mock(SkillCompatibilityService.class);
        AgentCatalog agents = mock(AgentCatalog.class);
        KnowledgeQueryService knowledge = mock(KnowledgeQueryService.class);
        NodeService nodes = mock(NodeService.class);
        ToolRouter tools = mock(ToolRouter.class);
        SkillExperienceService service = new SkillExperienceService(
                skills, analyzer, compatibility, agents, knowledge, nodes, tools);
        ActorContext actor = new ActorContext("tenant", "user", Set.of("LOCAL_USER"), Set.of());
        SkillRunBinding binding = new SkillRunBinding(
                "review", "Review", "", "sha256:" + "a".repeat(64), "local", "", "local", "content", "");
        SkillAnalysis analysis = new SkillAnalysis("review", 1, List.of(), List.of(), List.of(), List.of(),
                "none", List.of(), List.of(), List.of());
        CompatibilityReport report = new CompatibilityReport(true, List.of(), List.of(), List.of(), List.of());
        when(agents.get("default-assistant")).thenReturn(new AgentDefinitionView(
                "default-assistant", "Default", "", "", "model", "*", true));
        when(skills.resolveForRun(List.of("review"))).thenReturn(List.of(binding));
        when(knowledge.resolveKnowledgeBaseIds(List.of(), actor)).thenReturn(List.of());
        when(tools.resolve(any(), eq(List.of()), eq("*"))).thenReturn(List.of());
        when(analyzer.analyze(List.of(binding))).thenReturn(List.of(analysis));
        when(compatibility.check(List.of(analysis), List.of(), null)).thenReturn(report);

        SkillTestView result = service.test(
                new SkillPreflightCommand(List.of("review"), null, null, List.of(), List.of(), List.of()), actor);

        assertThat(result.preflight().ready()).isTrue();
        assertThat(result.checks()).extracting(SkillTestView.Check::status)
                .containsExactly("PASSED", "PASSED", "PASSED", "NOT_RUN");
        verify(nodes).validateExecutionTarget(null, actor);
    }
}
