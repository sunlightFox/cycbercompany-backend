package io.github.yourname.cycbercompany.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * OpenAI 兼容聊天接口的最小实现。
 *
 * <p>上层只依赖 {@link ModelGateway}，因此可以在不改编排代码的前提下接入 Spring AI、
 * Ollama 或其他供应商。本类直接构造 HTTP 协议请求，适合作为学习“模型配置如何变成一次
 * {@code /chat/completions} 调用”的清晰边界。
 */
@Service
class OpenAiCompatibleModelGateway implements ModelGateway {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    // This applies to both response headers and the SSE body. A provider that accepts a request
    // but never emits another event must not leave an interactive coding run blocked indefinitely.
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(45);

    private final ModelProfileRepository profiles;
    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;

    OpenAiCompatibleModelGateway(
            ModelProfileRepository profiles,
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper) {
        this.profiles = profiles;
        this.restClientBuilder = restClientBuilder;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }

    @Override
    public ModelAnswer complete(ModelCompletionRequest request) {
        // 调用前验证配置，避免把不支持文本或已禁用的模型请求发送到外部供应商。
        var profile = profiles.findById(request.modelProfileId())
                .orElseThrow(() -> new ModelGatewayException("Model profile not found: " + request.modelProfileId()));
        if (!profile.enabled()) {
            throw new ModelGatewayException("Model profile is disabled: " + profile.id());
        }
        if (!profile.capabilities().contains(ModelCapability.TEXT)) {
            throw new ModelGatewayException("Selected model does not advertise TEXT capability: " + profile.id());
        }
        if (!request.tools().isEmpty() && !profile.capabilities().contains(ModelCapability.TOOLS)) {
            throw new ModelGatewayException("Selected model does not advertise TOOLS capability: " + profile.id());
        }
        if (profile.providerType() != ProviderType.OPENAI_COMPATIBLE) {
            throw new ModelGatewayException("Provider is not implemented yet: " + profile.providerType());
        }

        // 数据库中保存的密钥优先；为空时才按 credentialRef 从环境变量读取，便于本地部署不落盘密钥。
        String apiKey = profile.apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getenv(profile.credentialRef());
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new ModelGatewayException("Missing model API key. Save it on the selected model profile or set environment variable " + profile.credentialRef());
        }
        String bearerToken = apiKey;

        try {
            // 这里的 Map 会由 RestClient/Jackson 序列化为 OpenAI 兼容的 JSON 请求体。
            Map<String, Object> payload = new HashMap<>();
            payload.put("model", profile.modelName());
            payload.put("messages", request.messages().stream()
                    .map(this::messagePayload)
                    .toList());
            if (!request.tools().isEmpty()) {
                payload.put("tools", request.tools().stream()
                        .map(tool -> Map.of(
                                "type", "function",
                                "function", Map.of(
                                        "name", tool.name(),
                                        "description", tool.description(),
                                        "parameters", tool.inputSchema())))
                        .toList());
                if (request.toolChoice() == ToolChoice.REQUIRED) {
                    payload.put("tool_choice", "required");
                }
            }

            var response = restClientBuilder
                    .baseUrl(trimTrailingSlash(profile.baseUrl()))
                    .requestFactory(timeoutRequestFactory())
                    .build()
                    .post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> headers.setBearerAuth(bearerToken))
                    .body(payload)
                    .retrieve()
                    .body(OpenAiChatCompletionResponse.class);

            // 兼容接口仍可能返回 HTTP 2xx 但不含 choices，因此要显式校验响应形状。
            if (response == null || response.choices() == null || response.choices().isEmpty()) {
                throw new ModelGatewayException("Model provider returned no choices.");
            }
            Choice choice = response.choices().getFirst();
            String content = choice.message().content();
            Integer promptTokens = response.usage() == null ? null : response.usage().prompt_tokens();
            Integer completionTokens = response.usage() == null ? null : response.usage().completion_tokens();
            return new ModelAnswer(
                    content == null ? "" : content,
                    promptTokens,
                    completionTokens,
                    response.model(),
                    parseToolCalls(choice.message().tool_calls()),
                    choice.finish_reason() == null ? "stop" : choice.finish_reason());
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 429) {
                throw new ModelRateLimitException(
                        "Model provider rate limited the request: " + ex.getMessage(),
                        retryAfter(ex.getResponseHeaders()),
                        ex);
            }
            if (isRetryableStatus(ex.getStatusCode().value())) {
                throw new ModelTransientException(
                        "Model provider temporarily failed with status " + ex.getStatusCode().value() + ".",
                        ex.getStatusCode().value(),
                        ex);
            }
            throw new ModelGatewayException("Model provider call failed: " + ex.getMessage(), ex);
        } catch (RestClientException ex) {
            throw new ModelTransientException("Model provider transport failed: " + ex.getMessage(), null, ex);
        }
    }

    @Override
    public ModelAnswer stream(ModelCompletionRequest request, Consumer<String> onToken) {
        var profile = profiles.findById(request.modelProfileId())
                .orElseThrow(() -> new ModelGatewayException("Model profile not found: " + request.modelProfileId()));
        if (!profile.enabled()) {
            throw new ModelGatewayException("Model profile is disabled: " + profile.id());
        }
        if (!profile.capabilities().contains(ModelCapability.TEXT)) {
            throw new ModelGatewayException("Selected model does not advertise TEXT capability: " + profile.id());
        }
        if (!request.tools().isEmpty() && !profile.capabilities().contains(ModelCapability.TOOLS)) {
            throw new ModelGatewayException("Selected model does not advertise TOOLS capability: " + profile.id());
        }
        if (profile.providerType() != ProviderType.OPENAI_COMPATIBLE) {
            throw new ModelGatewayException("Provider is not implemented yet: " + profile.providerType());
        }

        String apiKey = profile.apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getenv(profile.credentialRef());
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new ModelGatewayException("Missing model API key. Save it on the selected model profile or set environment variable " + profile.credentialRef());
        }

        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("model", profile.modelName());
            payload.put("messages", request.messages().stream().map(this::messagePayload).toList());
            payload.put("stream", true);
            if (!request.tools().isEmpty()) {
                payload.put("tools", request.tools().stream()
                        .map(tool -> Map.of(
                                "type", "function",
                                "function", Map.of(
                                        "name", tool.name(),
                                        "description", tool.description(),
                                        "parameters", tool.inputSchema())))
                        .toList());
                if (request.toolChoice() == ToolChoice.REQUIRED) {
                    payload.put("tool_choice", "required");
                }
            }

            var httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(trimTrailingSlash(profile.baseUrl()) + "/chat/completions"))
                    .timeout(READ_TIMEOUT)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
            var response = HttpClient.newBuilder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .build()
                    .send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() == 429) {
                throw new ModelRateLimitException(
                        "Model provider rate limited the request.",
                        retryAfter(response.headers().firstValue(HttpHeaders.RETRY_AFTER).orElse(null)),
                        null);
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                if (isRetryableStatus(response.statusCode())) {
                    throw new ModelTransientException(
                            "Model provider temporarily failed with status " + response.statusCode() + ".",
                            response.statusCode(),
                            null);
                }
                throw new ModelGatewayException("Model provider call failed with status " + response.statusCode() + ".");
            }

            var state = new StreamingAnswer();
            var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));
            boolean streamReadCompleted = false;
            try {
                readStreamWithTimeout(reader, state, onToken);
                streamReadCompleted = true;
            } finally {
                // A misbehaving provider can also block while closing a timed-out SSE body.
                // Do not turn cleanup into another unbounded wait on the run worker.
                if (streamReadCompleted) {
                    reader.close();
                } else {
                    closeReaderAsync(reader);
                }
            }
            if (!state.terminalSignalReceived) {
                throw new ModelTransientException(
                        "Model provider closed the stream before sending a completion signal.", null, null);
            }
            return new ModelAnswer(
                    state.content.toString(),
                    state.promptTokens,
                    state.completionTokens,
                    state.model,
                    state.toolCalls.complete(objectMapper),
                    state.finishReason == null ? "stop" : state.finishReason);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ModelGatewayException("Model provider stream was interrupted.", ex);
        } catch (IOException ex) {
            // Connect/read timeouts and socket resets are transport failures. Keep them
            // retryable so the bounded coding loop can recover from provider hiccups.
            throw new ModelTransientException("Model provider stream transport failed: " + ex.getMessage(), null, ex);
        }
    }

    private void readStreamWithTimeout(
            BufferedReader reader,
            StreamingAnswer state,
            Consumer<String> onToken) throws IOException, InterruptedException {
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            var task = executor.submit(() -> {
                try {
                    readStreamEvents(reader, state, onToken);
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            });
            try {
                task.get(READ_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException ex) {
                // Closing the body unblocks a virtual thread even when InputStream ignores interrupts.
                // It must not happen on this worker: a blocked socket close previously prevented
                // the timeout from reaching the coding loop and left runs stuck in RUNNING.
                task.cancel(true);
                closeReaderAsync(reader);
                throw new IOException("Model provider stream timed out after "
                        + READ_TIMEOUT.toSeconds() + " seconds without completing.", ex);
            } catch (ExecutionException ex) {
                if (ex.getCause() instanceof UncheckedIOException io) {
                    throw io.getCause();
                }
                throw new IOException("Failed while reading the model provider stream.", ex.getCause());
            }
        } finally {
            // ExecutorService.close() waits for every task. A provider may ignore interruption
            // while blocked in a socket read, so shutdown must remain non-blocking here.
            executor.shutdownNow();
        }
    }

    private static void closeReaderAsync(BufferedReader reader) {
        Thread.startVirtualThread(() -> {
            try {
                reader.close();
            } catch (IOException ignored) {
                // The original read failure is the actionable error; cleanup is best-effort.
            }
        });
    }

    private void readStreamEvents(
            BufferedReader reader,
            StreamingAnswer state,
            Consumer<String> onToken) throws IOException {
        StringBuilder data = new StringBuilder();
        String line;
        boolean stopped = false;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                if (consumeStreamEvent(data, state, onToken)) {
                    stopped = true;
                    break;
                }
                data.setLength(0);
            } else if (line.startsWith("data:")) {
                if (!data.isEmpty()) {
                    data.append('\n');
                }
                data.append(line.substring(5).stripLeading());
            }
        }
        if (!stopped) {
            consumeStreamEvent(data, state, onToken);
        }
    }

    /** @return true only for the OpenAI SSE end marker, which ends this response without waiting for socket close. */
    private boolean consumeStreamEvent(StringBuilder data, StreamingAnswer state, Consumer<String> onToken) {
        if (data.isEmpty()) {
            return false;
        }
        if ("[DONE]".contentEquals(data)) {
            state.terminalSignalReceived = true;
            return true;
        }
        try {
            JsonNode root = objectMapper.readTree(data.toString());
            if (root == null) {
                return false;
            }
            if (root.hasNonNull("model")) {
                state.model = root.get("model").asText();
            }
            JsonNode usage = root.get("usage");
            if (usage != null) {
                state.promptTokens = intValue(usage, "prompt_tokens", state.promptTokens);
                state.completionTokens = intValue(usage, "completion_tokens", state.completionTokens);
            }
            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                return false;
            }
            JsonNode choice = choices.get(0);
            boolean finished = choice.hasNonNull("finish_reason");
            if (finished) {
                state.finishReason = choice.get("finish_reason").asText();
                state.terminalSignalReceived = true;
            }
            JsonNode content = choice.path("delta").get("content");
            if (content != null && !content.isNull()) {
                String token = content.asText();
                if (!token.isEmpty()) {
                    state.content.append(token);
                    onToken.accept(token);
                }
            }
            // 工具调用与普通文本使用同一条 SSE 流，但它们不能混为 TOKEN_DELTA。
            // 组装器只保存协议字段，流结束后才把完整调用交给 CodingAgentLoop 执行。
            state.toolCalls.accept(choice.path("delta").get("tool_calls"));
            return finished;
        } catch (Exception ex) {
            throw new ModelGatewayException("Model provider returned an invalid stream event.", ex);
        }
    }

    private static Integer intValue(JsonNode node, String field, Integer fallback) {
        JsonNode value = node.get(field);
        return value != null && value.canConvertToInt() ? value.asInt() : fallback;
    }

    private static boolean isRetryableStatus(int statusCode) {
        return statusCode == 408 || statusCode == 409 || statusCode == 425
                || (statusCode >= 500 && statusCode <= 599);
    }

    private static final class StreamingAnswer {
        private final StringBuilder content = new StringBuilder();
        private final OpenAiStreamingToolCallAssembler toolCalls = new OpenAiStreamingToolCallAssembler();
        private Integer promptTokens;
        private Integer completionTokens;
        private String model;
        private String finishReason;
        private boolean terminalSignalReceived;
    }

    private static Duration retryAfter(HttpHeaders headers) {
        return retryAfter(headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER));
    }

    private static Duration retryAfter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Duration.ofSeconds(Math.max(0, Long.parseLong(value.trim())));
        } catch (NumberFormatException ignored) {
            try {
                Instant retryAt = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                Duration delay = Duration.between(Instant.now(), retryAt);
                return delay.isNegative() ? Duration.ZERO : delay;
            } catch (Exception ignoredDate) {
                return null;
            }
        }
    }

    private static JdkClientHttpRequestFactory timeoutRequestFactory() {
        // 连接超时和读取超时分开设置：前者限制建连，后者限制模型生成过程中长期无响应。
        var client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        var factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }

    private static String trimTrailingSlash(String value) {
        // baseUrl 既可配置为 /v1 也可配置为 /v1/，统一后避免拼出双斜杠。
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private List<ModelToolCall> parseToolCalls(List<ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return List.of();
        }
        return toolCalls.stream().map(call -> {
            if (call.function() == null || call.function().name() == null || call.function().name().isBlank()) {
                throw new ModelGatewayException("Model provider returned an invalid tool call.");
            }
            String arguments = call.function().arguments();
            try {
                Map<String, Object> parsed = arguments == null || arguments.isBlank()
                        ? Map.of()
                        : objectMapper.readValue(arguments, new TypeReference<Map<String, Object>>() {
                        });
                return new ModelToolCall(call.id(), call.function().name(), parsed);
            } catch (Exception ex) {
                throw new ModelGatewayException("Model provider returned invalid tool arguments: " + ex.getMessage(), ex);
            }
        }).toList();
    }

    private Map<String, Object> messagePayload(ModelMessage message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("role", message.role());
        payload.put("content", message.content());
        if (message.toolCallId() != null && !message.toolCallId().isBlank()) {
            payload.put("tool_call_id", message.toolCallId());
        }
        if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
            payload.put("tool_calls", message.toolCalls().stream()
                    .map(call -> Map.of(
                            "id", call.id(),
                            "type", "function",
                            "function", Map.of(
                                    "name", call.name(),
                                    "arguments", writeToolArguments(call.arguments()))))
                    .toList());
        }
        return payload;
    }

    private String writeToolArguments(Map<String, Object> arguments) {
        try {
            return objectMapper.writeValueAsString(arguments == null ? Map.of() : arguments);
        } catch (Exception ex) {
            throw new ModelGatewayException("Unable to serialize tool arguments: " + ex.getMessage(), ex);
        }
    }

    record OpenAiChatCompletionResponse(String model, List<Choice> choices, Usage usage) {
    }

    record Choice(Message message, String finish_reason) {
    }

    record Message(String role, String content, List<ToolCall> tool_calls) {
    }

    record ToolCall(String id, ToolFunction function) {
    }

    record ToolFunction(String name, String arguments) {
    }

    record Usage(Integer prompt_tokens, Integer completion_tokens, Integer total_tokens) {
    }
}
