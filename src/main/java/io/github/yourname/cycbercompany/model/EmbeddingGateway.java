package io.github.yourname.cycbercompany.model;

import java.util.List;

/**
 * Embedding 模型网关。
 *
 * <p>这里故意和聊天模型的 ModelGateway 分开：聊天模型返回自然语言，embedding 模型返回数字向量。
 * 后续如果接入本地模型、DashScope 原生接口或批量 embedding，只需要扩展这个边界即可。
 */
public interface EmbeddingGateway {

    EmbeddingAnswer embed(EmbeddingRequest request);

    record EmbeddingRequest(String modelProfileId, String input) {
    }

    record EmbeddingAnswer(List<Double> vector, Integer promptTokens, Integer totalTokens, String rawModel) {
    }
}
