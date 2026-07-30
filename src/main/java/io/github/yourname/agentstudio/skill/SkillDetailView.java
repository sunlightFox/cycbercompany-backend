package io.github.yourname.agentstudio.skill;

import java.util.List;

/**
 * Full skill details for review before a user enables or edits a skill.
 */
public record SkillDetailView(SkillView summary, String skillMarkdown, List<String> files) {
}
