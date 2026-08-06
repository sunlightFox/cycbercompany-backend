package io.github.yourname.agentstudio.skill;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class SkillMarketplaceServiceTest {

    @Test
    void returnsCuratedSourcesWhenLiveSourcesDegrade() {
        ObjectMapper objectMapper = new ObjectMapper();
        SkillRepositoryService repositories = new SkillRepositoryService(objectMapper) {
            @Override
            public List<SkillRepositoryView> curated() {
                return List.of(new SkillRepositoryView(
                        "openai-skills",
                        "OpenAI/skills",
                        "Official skills.",
                        "https://github.com/openai/skills",
                        "main",
                        0,
                        "OFFICIAL"));
            }

            @Override
            public List<SkillRepositoryView> search(SearchSkillRepositoriesCommand command) {
                throw new IllegalStateException("rate limited");
            }
        };
        ClawHubSkillService clawHub = new ClawHubSkillService(objectMapper) {
            @Override
            public List<ClawHubSkillView> search(String query, Integer limit) {
                throw new IllegalStateException("registry unavailable");
            }
        };
        SkillMarketplaceService service = new SkillMarketplaceService(repositories, clawHub);

        SkillMarketplaceView result = service.overview("browser", 12);

        assertThat(result.curatedRepositories()).isEmpty();
        assertThat(result.searchRepositories()).isEmpty();
        assertThat(result.skillHubSkills()).isEmpty();
        assertThat(result.clawHubSkills()).isEmpty();
        assertThat(result.sources()).extracting(SkillSourceSummaryView::id)
                .containsExactly("skillhub", "clawhub");
        assertThat(result.sources()).extracting(SkillSourceSummaryView::status)
                .containsExactly("READY", "DEGRADED");
    }
}
