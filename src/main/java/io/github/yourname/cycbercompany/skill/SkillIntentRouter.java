package io.github.yourname.cycbercompany.skill;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Selects enabled installed Skills from their public descriptions without an extra model call.
 * Explicit Run selections always take precedence; this is only the chat default when none are
 * supplied by the client or Agent manifest.
 */
@Service
public class SkillIntentRouter {

    private static final int MAX_AUTOMATIC_SKILLS = 3;
    private static final Pattern QUOTED_TRIGGER = Pattern.compile("[\\\"“”'‘’]([^\\\"“”'‘’]{2,48})[\\\"“”'‘’]");
    private static final List<String> NON_TRIGGER_PHRASES = List.of(
            "用户", "请求", "自动", "触发", "技能", "信息", "结果", "内容", "工具", "使用");

    private final SkillCatalog skills;

    public SkillIntentRouter(SkillCatalog skills) {
        this.skills = skills;
    }

    public List<String> select(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) {
            return List.of();
        }
        String request = userRequest.toLowerCase(Locale.ROOT);
        return skills.list().stream()
                .filter(SkillView::enabled)
                .map(skill -> new Candidate(skill, score(skill, request)))
                .filter(candidate -> candidate.score() > 0)
                .sorted(Comparator.comparingInt(Candidate::score).reversed()
                        .thenComparing(candidate -> candidate.skill().id(), String.CASE_INSENSITIVE_ORDER))
                .limit(MAX_AUTOMATIC_SKILLS)
                .map(candidate -> candidate.skill().id())
                .toList();
    }

    static boolean matches(SkillView skill, String userRequest) {
        return score(skill, userRequest == null ? "" : userRequest.toLowerCase(Locale.ROOT)) > 0;
    }

    private static int score(SkillView skill, String request) {
        if (skill == null || !skill.enabled() || request.isBlank()) {
            return 0;
        }
        int score = 0;
        for (String trigger : quotedTriggers(skill.description())) {
            if (request.contains(trigger)) {
                score += 20 + Math.min(trigger.length(), 12);
            }
        }
        for (String token : usefulTokens(skill.id() + " " + skill.name())) {
            if (request.contains(token)) {
                score += 6 + Math.min(token.length(), 8);
            }
        }
        return score;
    }

    private static List<String> quotedTriggers(String description) {
        LinkedHashSet<String> triggers = new LinkedHashSet<>();
        Matcher matcher = QUOTED_TRIGGER.matcher(description == null ? "" : description.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String trigger = matcher.group(1).trim();
            if (!trigger.isBlank() && NON_TRIGGER_PHRASES.stream().noneMatch(trigger::equals)) {
                triggers.add(trigger);
            }
        }
        return List.copyOf(triggers);
    }

    private static List<String> usefulTokens(String text) {
        List<String> tokens = new ArrayList<>();
        for (String token : (text == null ? "" : text.toLowerCase(Locale.ROOT)).split("[^a-z0-9\\p{IsHan}]+")) {
            if (token.length() >= 3 && NON_TRIGGER_PHRASES.stream().noneMatch(token::equals)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private record Candidate(SkillView skill, int score) {
    }
}
