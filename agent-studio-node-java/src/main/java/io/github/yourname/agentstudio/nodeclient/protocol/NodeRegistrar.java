package io.github.yourname.agentstudio.nodeclient.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.yourname.agentstudio.nodeclient.NodeConfig;
import io.github.yourname.agentstudio.nodeclient.SystemInfo;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;

public class NodeRegistrar {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public NodeRegistrar(ObjectMapper objectMapper, HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    public NodeConfig register(String serverUrl, String registrationToken, String name, SystemInfo systemInfo) throws Exception {
        String normalizedServer = trimTrailingSlash(serverUrl);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("registrationToken", registrationToken);
        payload.put("name", name);
        payload.put("hostname", systemInfo.hostname());
        payload.put("osName", systemInfo.osName());
        payload.put("osArch", systemInfo.osArch());
        payload.put("clientVersion", systemInfo.clientVersion());

        HttpRequest request = HttpRequest.newBuilder(URI.create(normalizedServer + "/api/v1/nodes/register"))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Node registration failed: HTTP " + response.statusCode() + " " + response.body());
        }
        RegisterNodeResult result = objectMapper.readValue(response.body(), RegisterNodeResult.class);
        return new NodeConfig(
                normalizedServer,
                result.nodeId(),
                result.nodeSecret(),
                result.websocketUrl(),
                name,
                null);
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RegisterNodeResult(String nodeId, String nodeSecret, String websocketUrl) {
    }
}
