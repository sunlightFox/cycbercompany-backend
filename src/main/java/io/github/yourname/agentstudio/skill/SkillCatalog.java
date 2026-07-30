package io.github.yourname.agentstudio.skill;

import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Placeholder for declarative SKILL.md loading.
 *
 * <p>The backend exposes the module now so the architecture boundary exists
 * before script execution or hot reload is added. First-version skills are
 * prompt resources, not arbitrary code execution.
 */
@Service
public class SkillCatalog {
    public List<String> list() {
        return List.of();
    }
}
