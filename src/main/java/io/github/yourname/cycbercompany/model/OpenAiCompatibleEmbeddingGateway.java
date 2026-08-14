package io.github.yourname.cycbercompany.model;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * OpenAI-compatible embeddings 网关。
 *
 * <p>很多模型供应商会复用 OpenAI 的 /embeddings 协议，因此先实现这条“最大公约数”路径。
 * 如果某个供应商只有私有协议，后续可以新增对应实现，而不需要改知识库模块的业务代码。
 */
@Service
class OpenAiCompatibleEmbeddingGateway implements EmbeddingGateway {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(45);

    private final ModelProfileRepository profiles;
    private final RestClient.Builder restClientBuilder;

    OpenAiCompatibleEmbeddingGateway(ModelProfileRepository profiles, RestClient.Builder restClientBuilder) {
        this.profiles = profiles;
        this.restClientBuilder = restClientBuilder;
    }

    @Override
    public EmbeddingAnswer embed(EmbeddingRequest request) {
        var profile = profiles.findById(request.modelProfileId())
                .orElseThrow(() -> new ModelGatewayException("Embedding model profile not found: " + request.modelProfileId()));
        if (!profile.enabled()) {
            throw new ModelGatewayException("Embedding model profile is disabled: " + profile.id());
        }
        if (!profile.capabilities().contains(ModelCapability.EMBEDDING)) {
            throw new ModelGatewayException("Selected model does not advertise EMBEDDING capability: " + profile.id());
        }
        if (profile.providerType() != ProviderType.OPENAI_COMPATIBLE) {
            throw new ModelGatewayException("Embedding provider is not implemented yet: " + profile.providerType());
        }

        String apiKey = profile.apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getenv(profile.credentialRef());
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new ModelGatewayException("Missing embedding API key. Save it on the selected model profile or set environment variable " + profile.credentialRef());
        }
        String bearerToken = apiKey;

        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("model", profile.modelName());
            payload.put("input", request.input());

            var response = restClientBuilder
                    .baseUrl(trimTrailingSlash(profile.baseUrl()))
                    .requestFactory(timeoutRequestFactory())
                    .build()
                    .post()
                    .uri("/embeddings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> headers.setBearerAuth(bearerToken))
                    .body(payload)
                    .retrieve()
                    .body(OpenAiEmbeddingResponse.class);

            if (response == null || response.data() == null || response.data().isEmpty()
                    || response.data().getFirst().embedding() == null || response.data().getFirst().embedding().isEmpty()) {
                throw new ModelGatewayException("Embedding provider returned no vector.");
            }
            Integer promptTokens = response.usage() == null ? null : response.usage().prompt_tokens();
            Integer totalTokens = response.usage() == null ? null : response.usage().total_tokens();
            return new EmbeddingAnswer(response.data().getFirst().embedding(), promptTokens, totalTokens, response.model());
        } catch (RestClientException ex) {
            throw new ModelGatewayException("Embedding provider call failed: " + ex.getMessage(), ex);
        }
    }

    private static JdkClientHttpRequestFactory timeoutRequestFactory() {
        var client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        var factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    record OpenAiEmbeddingResponse(String model, List<EmbeddingData> data, Usage usage) {
    }

    record EmbeddingData(List<Double> embedding, Integer index) {
    }

    record Usage(Integer prompt_tokens, Integer total_tokens) {
    }
}
