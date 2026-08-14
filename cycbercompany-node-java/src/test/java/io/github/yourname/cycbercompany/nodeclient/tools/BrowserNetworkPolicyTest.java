package io.github.yourname.cycbercompany.nodeclient.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BrowserNetworkPolicyTest {

    @Test
    void blocksNonHttpPrivateAndMetadataTargetsByDefault() {
        for (String url : List.of(
                "file:///etc/passwd",
                "data:text/plain,secret",
                "http://localhost:8080",
                "http://127.0.0.1:8080",
                "http://10.0.0.10",
                "http://169.254.169.254/latest/meta-data")) {
            assertThrows(IllegalArgumentException.class, () -> BrowserNetworkPolicy.requireAllowed(url, Set.of()));
        }
    }

    @Test
    void readsOnlyTheHiddenServerPolicyAndAllowsThatExactHost() {
        Map<String, Object> arguments = Map.of(
                BrowserNetworkPolicy.POLICY_ARGUMENT,
                Map.of(BrowserNetworkPolicy.ALLOWED_PRIVATE_HOSTS, List.of("127.0.0.1")));

        Set<String> allowed = BrowserNetworkPolicy.allowedPrivateHosts(arguments);

        assertEquals(Set.of("127.0.0.1"), allowed);
        assertEquals("http://127.0.0.1:8080/app", BrowserNetworkPolicy.requireAllowed(
                "http://127.0.0.1:8080/app", allowed));
        assertThrows(IllegalArgumentException.class, () -> BrowserNetworkPolicy.requireAllowed(
                "http://127.0.0.2:8080/app", allowed));
    }
}
