package io.github.yourname.agentstudio.nodeclient.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yourname.agentstudio.nodeclient.NodeConfig;
import io.github.yourname.agentstudio.nodeclient.SystemInfo;
import java.net.URI;
import java.net.http.HttpClient;
import org.junit.jupiter.api.Test;

class NodeWebSocketClientSecurityTest {

    @Test
    void removesLegacySecretQueryBeforeConnectingOrLogging() {
        NodeConfig config = new NodeConfig(
                "http://localhost:8080",
                "node-1",
                "ns_do_not_log_me",
                "/api/v1/node-channel?nodeId=node-1&nodeSecret=ns_do_not_log_me",
                "test-node",
                null,
                null);
        NodeWebSocketClient client = new NodeWebSocketClient(
                new ObjectMapper(), HttpClient.newHttpClient(), config, SystemInfo.current());

        URI uri = client.websocketUri();

        assertEquals("ws://localhost:8080/api/v1/node-channel", uri.toString());
        assertEquals("ws://localhost:8080", NodeWebSocketClient.safeServerAddress(uri));
        assertFalse(uri.toString().contains(config.nodeSecret()));
    }
}
