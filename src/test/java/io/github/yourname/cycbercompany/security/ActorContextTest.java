package io.github.yourname.cycbercompany.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ActorContextTest {

    @Test
    void treatsNullRoleAndScopeEntriesAsOmitted() {
        Set<String> roles = new LinkedHashSet<>();
        roles.add("LOCAL_USER");
        roles.add(null);
        Set<String> scopes = new LinkedHashSet<>();
        scopes.add(null);
        scopes.add("agent:run");

        ActorContext actor = new ActorContext("tenant", "user", roles, scopes);

        assertThat(actor.roles()).containsExactly("LOCAL_USER");
        assertThat(actor.scopes()).containsExactly("agent:run");
    }

    @Test
    void defaultsNullRoleAndScopeSetsToEmptySets() {
        ActorContext actor = new ActorContext("tenant", "user", null, null);

        assertThat(actor.roles()).isEmpty();
        assertThat(actor.scopes()).isEmpty();
    }
}
