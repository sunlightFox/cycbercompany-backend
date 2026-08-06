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
    private final String apiToken;

    public NodeRegistrar(ObjectMapper objectMapper, HttpClient httpClient) {
        this(objectMapper, httpClient, System.getenv("AGENT_STUDIO_API_TOKEN"));
    }

    public NodeRegistrar(ObjectMapper objectMapper, HttpClient httpClient, String apiToken) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.apiToken = apiToken == null ? "" : apiToken.trim();
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

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(normalizedServer + "/api/v1/nodes/register"))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)));
        addApiToken(requestBuilder);
        HttpRequest request = requestBuilder.build();
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
                null,
                null);
    }

    /** Provisions the implicit companion used by a personal local installation. */
    public NodeConfig bootstrapLocalExecutor(String serverUrl, String name, SystemInfo systemInfo) throws Exception {
        requireLoopbackServer(serverUrl);
        String normalizedServer = trimTrailingSlash(serverUrl);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        payload.put("hostname", systemInfo.hostname());
        payload.put("osName", systemInfo.osName());
        payload.put("osArch", systemInfo.osArch());
        payload.put("clientVersion", systemInfo.clientVersion());
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(normalizedServer + "/api/v1/local-executor/bootstrap"))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)));
        addApiToken(requestBuilder);
        HttpRequest request = requestBuilder.build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Local executor provisioning failed: HTTP "
                    + response.statusCode() + " " + response.body());
        }
        RegisterNodeResult result = objectMapper.readValue(response.body(), RegisterNodeResult.class);
        return new NodeConfig(
                normalizedServer,
                result.nodeId(),
                result.nodeSecret(),
                result.websocketUrl(),
                name,
                null,
                null);
    }

    static void requireLoopbackServer(String serverUrl) {
        if (serverUrl == null || serverUrl.isBlank()) {
            throw new IllegalArgumentException(
                    "start-local requires a loopback server URL such as http://127.0.0.1:8080.");
        }
        URI uri;
        try {
            uri = URI.create(serverUrl.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("start-local requires a valid loopback HTTP(S) server URL.", ex);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.ROOT);
        boolean loopback = "localhost".equals(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host)
                || "[::1]".equals(host);
        if (!(("http".equals(scheme) || "https".equals(scheme)) && loopback)) {
            throw new IllegalArgumentException(
                    "start-local can provision only through a loopback HTTP(S) URL. "
                            + "Use http://127.0.0.1:<port> or http://localhost:<port>.");
        }
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private void addApiToken(HttpRequest.Builder requestBuilder) {
        if (!apiToken.isBlank()) {
            requestBuilder.header("Authorization", "Bearer " + apiToken);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RegisterNodeResult(String nodeId, String nodeSecret, String websocketUrl) {
    }
}
