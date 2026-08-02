package io.github.yourname.agentstudio.skill;

import java.util.List;

/** Safe static Skill test result. Script execution remains an explicit Run action with approval. */
public record SkillTestView(SkillPreflightView preflight, List<Check> checks) {

    public SkillTestView {
        checks = checks == null ? List.of() : List.copyOf(checks);
    }

    public record Check(String name, String status, String message) {
    }
}
