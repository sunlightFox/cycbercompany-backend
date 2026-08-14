package io.github.yourname.cycbercompany.tool;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WebSearchQueryPlannerTest {

    @Test
    void fansOutBroadChineseNewsAcrossComplementaryTopics() {
        var plan = WebSearchQueryPlanner.plan("今日最新资讯", "", WebSearchMode.NEWS, 4);

        assertThat(plan).extracting(WebSearchQueryPlanner.PlannedQuery::query)
                .containsExactly("今日最新资讯", "今日要闻", "今日科技新闻", "今日财经国际新闻");
        assertThat(plan.getFirst().mode()).isEqualTo(WebSearchMode.NEWS);
        assertThat(plan.subList(1, plan.size())).allMatch(item -> item.mode() == WebSearchMode.GENERAL);
    }

    @Test
    void addsFoodIndustryVocabularyToFocusedNews() {
        var plan = WebSearchQueryPlanner.plan("今日最新美食新闻", "美食", WebSearchMode.NEWS, 3);

        assertThat(plan).extracting(WebSearchQueryPlanner.PlannedQuery::query)
                .containsExactly("今日最新美食新闻", "美食 今日新闻 最新消息", "美食 餐饮 食品 今日动态");
    }

    @Test
    void leavesNonCurrentTechnicalSearchAsOneQuery() {
        var plan = WebSearchQueryPlanner.plan("Spring Framework reference", "Spring Framework reference",
                WebSearchMode.TECHNICAL, 4);

        assertThat(plan).singleElement()
                .extracting(WebSearchQueryPlanner.PlannedQuery::query)
                .isEqualTo("Spring Framework reference");
    }

    @Test
    void addsAnAiSpecificExpansionForCurrentAiNews() {
        var plan = WebSearchQueryPlanner.plan("today AI news", "AI", WebSearchMode.NEWS, 3);

        assertThat(plan).extracting(WebSearchQueryPlanner.PlannedQuery::query)
                .contains("AI large language model generative AI current news");
    }
}
