package io.github.yourname.agentstudio.skill;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class SkillRepositoryServiceTest {

    @Test
    void curatedSourcesCoverABroaderSkillMarketplaceSurface() {
        SkillRepositoryService service = new SkillRepositoryService(new ObjectMapper());

        assertThat(service.curated())
                .hasSizeGreaterThanOrEqualTo(10)
                .extracting(SkillRepositoryView::name)
                .contains(
                        "OpenAI/skills",
                        "anthropics/skills",
                        "microsoft/skills",
                        "supabase/agent-skills",
                        "VoltAgent/awesome-agent-skills");
    }
}
