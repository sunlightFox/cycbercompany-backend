package io.github.yourname.agentstudio.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.yourname.agentstudio.security.ActorContext;
import io.github.yourname.agentstudio.tool.CodingWorkspaceScope;
import io.github.yourname.agentstudio.tool.ResolvedToolBinding;
import io.github.yourname.agentstudio.tool.RiskLevel;
import io.github.yourname.agentstudio.tool.ToolInvocationRequest;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SkillAuthoringToolProviderTest {

    @Test
    void savesOnlyDisabledDraftsAfterTheApprovalLayerInvokesIt() {
        SkillCatalog catalog = mock(SkillCatalog.class);
        SkillAuthoringToolProvider provider = new SkillAuthoringToolProvider(catalog);
        SkillView draft = new SkillView("draft", "Draft", "", false, Instant.now(), "local/authoring", "", "local",
                "content:abc", "sha256:" + "a".repeat(64), "", 1, 20);
        when(catalog.create(new CreateSkillCommand("draft", "# Draft", false, false))).thenReturn(draft);
        ResolvedToolBinding binding = new ResolvedToolBinding(
                "skill-authoring:create-draft", "tool_create", "skill.create_draft", "skill-authoring", "create-draft",
                "", RiskLevel.MEDIUM, true, Map.of(), Map.of());

        var result = provider.invoke(new ToolInvocationRequest(
                "run", "call", binding, Map.of("id", "draft", "skillMarkdown", "# Draft"), null,
                CodingWorkspaceScope.from(null), new ActorContext("tenant", "user", Set.of("LOCAL_USER"), Set.of())));

        assertThat(result.succeeded()).isTrue();
        assertThat(result.result()).containsKey("skill");
        verify(catalog).create(new CreateSkillCommand("draft", "# Draft", false, false));
    }
}
