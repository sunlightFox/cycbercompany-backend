package io.github.yourname.agentstudio.model;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Minimal OpenAI-compatible chat gateway.
 *
 * <p>Spring AI can be introduced behind this same interface later. For the
 * current backend, using the wire protocol directly keeps the model boundary
 * explicit and makes it obvious how the EdgeFn/MiniMax configuration maps to a
 * real HTTP call.
 */
@Service
class OpenAiCompatibleModelGateway implements ModelGateway {

    private final ModelProfileRepository profiles;
    private final RestClient.Builder restClientBuilder;

    OpenAiCompatibleModelGateway(ModelProfileRepository profiles, RestClient.Builder restClientBuilder) {
        this.profiles = profiles;
        this.restClientBuilder = restClientBuilder;
    }

    @Override
    public ModelAnswer complete(ModelCompletionRequest request) {
        var profile = profiles.findById(request.modelProfileId())
                .orElseThrow(() -> new ModelGatewayException("Model profile not found: " + request.modelProfileId()));
        if (!profile.enabled()) {
            throw new ModelGatewayException("Model profile is disabled: " + profile.id());
        }
        if (!profile.capabilities().contains(ModelCapability.TEXT)) {
            throw new ModelGatewayException("Selected model does not advertise TEXT capability: " + profile.id());
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
        String bearerToken = apiKey;

        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("model", profile.modelName());
            payload.put("messages", request.messages().stream()
                    .map(message -> Map.of("role", message.role(), "content", message.content()))
                    .toList());

            var response = restClientBuilder
                    .baseUrl(trimTrailingSlash(profile.baseUrl()))
                    .build()
                    .post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> headers.setBearerAuth(bearerToken))
                    .body(payload)
                    .retrieve()
                    .body(OpenAiChatCompletionResponse.class);

            if (response == null || response.choices() == null || response.choices().isEmpty()) {
                throw new ModelGatewayException("Model provider returned no choices.");
            }
            String content = response.choices().getFirst().message().content();
            Integer promptTokens = response.usage() == null ? null : response.usage().prompt_tokens();
            Integer completionTokens = response.usage() == null ? null : response.usage().completion_tokens();
            return new ModelAnswer(content == null ? "" : content, promptTokens, completionTokens, response.model());
        } catch (RestClientException ex) {
            throw new ModelGatewayException("Model provider call failed: " + ex.getMessage(), ex);
        }
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    record OpenAiChatCompletionResponse(String model, List<Choice> choices, Usage usage) {
    }

    record Choice(Message message) {
    }

    record Message(String role, String content) {
    }

    record Usage(Integer prompt_tokens, Integer completion_tokens, Integer total_tokens) {
    }
}
