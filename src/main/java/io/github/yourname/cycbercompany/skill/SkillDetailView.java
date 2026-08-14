package io.github.yourname.cycbercompany.skill;

import java.util.List;

/**
 * Full skill details for review before a user enables or edits a skill.
 */
public record SkillDetailView(SkillView summary, String skillMarkdown, List<String> files) {
}
