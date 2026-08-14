package io.github.yourname.cycbercompany.skill;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Builds the UI payload from the configured online registries in priority order. */
@Service
public class SkillMarketplaceService {

    private static final Logger log = LoggerFactory.getLogger(SkillMarketplaceService.class);

    private final SkillRepositoryService repositories;
    private final ClawHubSkillService clawHubSkills;
    private final SkillHubSkillService skillHubSkills;

    @Autowired
    public SkillMarketplaceService(SkillRepositoryService repositories, ClawHubSkillService clawHubSkills, SkillHubSkillService skillHubSkills) {
        this.repositories = repositories;
        this.clawHubSkills = clawHubSkills;
        this.skillHubSkills = skillHubSkills;
    }

    /** Compatibility constructor for callers that do not enable remote registries in tests. */
    public SkillMarketplaceService(SkillRepositoryService repositories, ClawHubSkillService clawHubSkills) {
        this(repositories, clawHubSkills, new SkillHubSkillService(new com.fasterxml.jackson.databind.ObjectMapper()) {
            @Override
            public List<SkillHubSkillView> search(String query, Integer limit) { return List.of(); }
        });
    }

    public SkillMarketplaceView overview(String query, Integer limit) {
        int boundedLimit = clamp(limit, 1, 100, 100);
        String normalizedQuery = query == null ? "" : query.trim();

        Retrieved<List<SkillHubSkillView>> skillHub = retrieve(
                () -> skillHubSkills.search(normalizedQuery, boundedLimit), List.of(), "skillhub");
        Retrieved<List<ClawHubSkillView>> clawHub = retrieve(
                () -> clawHubSkills.search(normalizedQuery, boundedLimit),
                List.of(),
                "clawhub");

        List<SkillSourceSummaryView> sources = new ArrayList<>();
        sources.add(new SkillSourceSummaryView("skillhub", "SkillHub", "SkillHub curated and community skills.",
                "SKILLHUB", skillHub.value().size(), skillHub.status(), "https://skillhub.cn/", skillHub.note()));
        sources.add(new SkillSourceSummaryView(
                "clawhub",
                "ClawHub registry",
                "Trending and query search across the public registry.",
                "CLAWHUB",
                clawHub.value().size(),
                clawHub.status(),
                "https://clawhub.ai",
                clawHub.note()));

        return new SkillMarketplaceView(
                normalizedQuery,
                boundedLimit,
                Instant.now(),
                List.of(),
                List.of(),
                skillHub.value(),
                clawHub.value(),
                sources);
    }

    private <T> Retrieved<T> retrieve(Supplier<T> operation, T emptyValue, String sourceId) {
        try {
            return new Retrieved<>(operation.get(), "READY", "");
        } catch (RuntimeException ex) {
            log.warn("Skill marketplace source {} failed: {}", sourceId, ex.getMessage());
            return new Retrieved<>(emptyValue, "DEGRADED", ex.getMessage());
        }
    }

    private static int clamp(Integer value, int min, int max, int fallback) {
        int resolved = value == null ? fallback : value;
        return Math.max(min, Math.min(max, resolved));
    }

    private record Retrieved<T>(T value, String status, String note) {
    }
}
