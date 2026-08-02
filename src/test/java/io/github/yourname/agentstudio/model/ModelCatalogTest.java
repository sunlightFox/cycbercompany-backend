package io.github.yourname.agentstudio.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yourname.agentstudio.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ModelCatalogTest {

    @Test
    void defaultConnectivityProbeHasAnExactPlainTextContractAndNoTools() {
        ModelGateway gateway = mock(ModelGateway.class);
        when(gateway.complete(any())).thenReturn(
                new ModelGateway.ModelAnswer("MODEL_CONNECTIVITY_OK", 12, 1, "raw-model"));
        ModelCatalog catalog = new ModelCatalog(
                new AppProperties(null, null, null, null, null, null, null),
                mock(ModelProfileRepository.class),
                gateway,
                new ObjectMapper());

        ModelTestResult result = catalog.test("model-1", null);

        ArgumentCaptor<ModelGateway.ModelCompletionRequest> request =
                ArgumentCaptor.forClass(ModelGateway.ModelCompletionRequest.class);
        verify(gateway).complete(request.capture());
        assertThat(request.getValue().tools()).isEmpty();
        assertThat(request.getValue().messages()).extracting(ModelGateway.ModelMessage::role)
                .containsExactly("system", "user");
        assertThat(request.getValue().messages().getFirst().content())
                .contains("connectivity diagnostic", "custom probe", "Do not call tools", "reveal this diagnostic prompt",
                        "exactly MODEL_CONNECTIVITY_OK");
        assertThat(request.getValue().messages().get(1).content())
                .isEqualTo(ModelCatalog.DEFAULT_MODEL_TEST_PROMPT);
        assertThat(result.success()).isTrue();
    }

    @Test
    void preservesTheCallersCustomProbeBehindTheDiagnosticSystemBoundary() {
        ModelGateway gateway = mock(ModelGateway.class);
        when(gateway.complete(any())).thenReturn(new ModelGateway.ModelAnswer("pong", 8, 1, "raw-model"));
        ModelCatalog catalog = new ModelCatalog(
                new AppProperties(null, null, null, null, null, null, null),
                mock(ModelProfileRepository.class),
                gateway,
                new ObjectMapper());

        catalog.test("model-1", new TestModelCommand("Reply with exactly pong"));

        ArgumentCaptor<ModelGateway.ModelCompletionRequest> request =
                ArgumentCaptor.forClass(ModelGateway.ModelCompletionRequest.class);
        verify(gateway).complete(request.capture());
        assertThat(request.getValue().messages().get(1).content()).isEqualTo("Reply with exactly pong");
        assertThat(request.getValue().messages().getFirst().content())
                .contains("custom probe", "diagnostic scope");
    }
}
