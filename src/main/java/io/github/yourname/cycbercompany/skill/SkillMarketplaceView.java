package io.github.yourname.cycbercompany.skill;

import java.time.Instant;
import java.util.List;

/**
 * Aggregated skill marketplace payload for the UI.
 */
public record SkillMarketplaceView(
        String query,
        int limit,
        Instant generatedAt,
        List<SkillRepositoryView> curatedRepositories,
        List<SkillRepositoryView> searchRepositories,
        List<SkillHubSkillView> skillHubSkills,
        List<ClawHubSkillView> clawHubSkills,
        List<SkillSourceSummaryView> sources) {

    public SkillMarketplaceView {
        query = query == null ? "" : query;
        curatedRepositories = curatedRepositories == null ? List.of() : List.copyOf(curatedRepositories);
        searchRepositories = searchRepositories == null ? List.of() : List.copyOf(searchRepositories);
        skillHubSkills = skillHubSkills == null ? List.of() : List.copyOf(skillHubSkills);
        clawHubSkills = clawHubSkills == null ? List.of() : List.copyOf(clawHubSkills);
        sources = sources == null ? List.of() : List.copyOf(sources);
    }
}
