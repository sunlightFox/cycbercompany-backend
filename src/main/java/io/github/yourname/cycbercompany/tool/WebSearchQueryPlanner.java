package io.github.yourname.cycbercompany.tool;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Produces a small, deterministic query fan-out without spending an LLM call. */
final class WebSearchQueryPlanner {

    private WebSearchQueryPlanner() {
    }

    static List<PlannedQuery> plan(String query, String topic, WebSearchMode intent, int requestedMaxQueries) {
        int maxQueries = Math.min(4, Math.max(1, requestedMaxQueries));
        List<PlannedQuery> planned = new ArrayList<>(maxQueries);
        Set<String> seen = new LinkedHashSet<>();
        add(planned, seen, query, intent, "tavily/" + categoryFor(intent));

        if ((intent != WebSearchMode.NEWS && intent != WebSearchMode.RECENT) || maxQueries == 1) {
            return List.copyOf(planned);
        }

        boolean chinese = containsHan(query);
        String normalizedTopic = topic == null ? "" : topic.trim();
        if (normalizedTopic.isBlank()) {
            add(planned, seen, chinese ? "今日要闻" : "today top stories",
                    WebSearchMode.GENERAL, "tavily/general/expanded-1");
            add(planned, seen, chinese ? "今日科技新闻" : "today technology news",
                    WebSearchMode.GENERAL, "tavily/general/expanded-2");
            add(planned, seen, chinese ? "今日财经国际新闻" : "today business world news",
                    WebSearchMode.GENERAL, "tavily/general/expanded-3");
        } else {
            String first = chinese
                    ? normalizedTopic + " 今日新闻 最新消息"
                    : normalizedTopic + " latest news today";
            add(planned, seen, first, WebSearchMode.GENERAL, "tavily/general/expanded-1");
            add(planned, seen, verticalQuery(normalizedTopic, chinese),
                    WebSearchMode.GENERAL, "tavily/general/expanded-2");
        }
        return List.copyOf(planned.subList(0, Math.min(maxQueries, planned.size())));
    }

    private static String verticalQuery(String topic, boolean chinese) {
        String normalized = topic.toLowerCase(Locale.ROOT);
        if (normalized.contains("ai") || normalized.contains("artificial intelligence")) {
            return chinese ? "人工智能 大模型 生成式 AI 今日动态" : "AI large language model generative AI current news";
        }
        if (normalized.contains("美食") || normalized.contains("餐饮") || normalized.contains("food")) {
            return chinese ? "美食 餐饮 食品 今日动态" : "food restaurant industry latest news";
        }
        if (normalized.contains("体育") || normalized.contains("sport")) {
            return chinese ? "体育 赛事 今日赛况 最新消息" : "sports results latest news today";
        }
        if (normalized.contains("科技") || normalized.contains("技术") || normalized.contains("tech")) {
            return chinese ? "科技 人工智能 互联网 今日动态" : "technology AI internet latest news";
        }
        if (normalized.contains("财经") || normalized.contains("商业") || normalized.contains("finance")) {
            return chinese ? "财经 商业 市场 今日动态" : "finance business markets latest news";
        }
        return chinese ? topic + " 行业动态 最新进展" : topic + " latest developments update";
    }

    private static void add(
            List<PlannedQuery> planned,
            Set<String> seen,
            String query,
            WebSearchMode mode,
            String sourceId) {
        String normalized = query == null ? "" : query.replaceAll("\\s+", " ").trim();
        if (!normalized.isBlank() && seen.add(normalized.toLowerCase(Locale.ROOT))) {
            planned.add(new PlannedQuery(normalized, mode, sourceId));
        }
    }

    private static boolean containsHan(String text) {
        return text != null && text.codePoints()
                .anyMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }

    private static String categoryFor(WebSearchMode mode) {
        return mode == WebSearchMode.NEWS ? "news" : "general";
    }

    record PlannedQuery(String query, WebSearchMode mode, String sourceId) {
    }
}
