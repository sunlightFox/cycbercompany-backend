package io.github.yourname.cycbercompany.mod;

import io.github.yourname.cycbercompany.security.ActorContext;
import java.util.Map;

/**
 * Generic Mod-owned state boundary. A Mod decides how its resource state is
 * stored; the platform only passes structured state through this port.
 */
public interface ModStateStore {
    Map<String, Object> load(String modId, String resourceId, ActorContext actor);

    void save(String modId, String resourceId, String event, Map<String, Object> state, ActorContext actor);
}
