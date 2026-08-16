package io.github.yourname.cycbercompany.skill;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class SkillIntentRouterTest {

    private final SkillView webSearch = new SkillView(
            "web-search", "web-search",
            "执行网页搜索并汇总结果。当用户要求\"搜索\"、\"查一下\"、\"找一下\"、\"帮我查\"、\"搜索一下\"等意图时触发此技能。",
            true, Instant.EPOCH, "", "", "", "", "", "", 1, 1);

    @Test
    void matchesAQuotedChineseTriggerFromTheSkillDescription() {
        assertThat(SkillIntentRouter.matches(webSearch, "帮我搜索一下今日 AI 资讯")).isTrue();
    }

    @Test
    void doesNotApplyTheSearchSkillToAnUnrelatedToolsQuestion() {
        assertThat(SkillIntentRouter.matches(webSearch, "你有什么工具？")).isFalse();
    }
}
