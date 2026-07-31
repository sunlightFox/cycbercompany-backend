package io.github.yourname.agentstudio.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    // Tool-oriented coding turns often ask the provider to inspect a large context
    // and emit structured calls. Give them a practical response window.
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(90);

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
            throw new ModelGatewayException("Model provider call failed: " + ex.getMessage(), ex);
        } catch (RestClientException ex) {
            throw new ModelGatewayException("Model provider call failed: " + ex.getMessage(), ex);
        }
    }

    private static Duration retryAfter(HttpHeaders headers) {
        if (headers == null) {
            return null;
        }
        String value = headers.getFirst(HttpHeaders.RETRY_AFTER);
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
