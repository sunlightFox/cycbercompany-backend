package io.github.yourname.agentstudio.skill;

/**
 * Small patch-style command for toggling a locally installed skill.
 */
public record UpdateSkillCommand(boolean enabled) {
}
