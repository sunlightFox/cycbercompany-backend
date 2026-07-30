package io.github.yourname.agentstudio.model;

import java.util.List;

public interface ModelGateway {

    ModelAnswer complete(ModelCompletionRequest request);

    record ModelCompletionRequest(String modelProfileId, List<ModelMessage> messages) {
    }

    record ModelMessage(String role, String content) {
    }

    record ModelAnswer(String content, Integer promptTokens, Integer completionTokens, String rawModel) {
    }
}
