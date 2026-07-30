package io.github.yourname.agentstudio.skill;

/**
 * Search command for finding public skill repositories on GitHub.
 */
public record SearchSkillRepositoriesCommand(String query, Integer limit) {
}
