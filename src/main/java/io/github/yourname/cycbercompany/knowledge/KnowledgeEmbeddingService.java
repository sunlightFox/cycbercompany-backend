package io.github.yourname.cycbercompany.knowledge;

import io.github.yourname.cycbercompany.config.AppProperties;
import io.github.yourname.cycbercompany.model.EmbeddingGateway;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * 知识库 embedding 编排服务。
 *
 * <p>这个类是“业务模块”和“模型模块”之间的缓冲层：
 * 知识库只关心“给文本生成/解析向量”，不用知道底层是 OpenAI-compatible、Ollama 还是未来的私有接口。
 */
@Service
public class KnowledgeEmbeddingService {

    private static final int DEFAULT_MAX_EMBEDDING_CHARS = 6_000;
    private static final double DEFAULT_VECTOR_WEIGHT = 1.0;

    private final AppProperties properties;
    private final KnowledgeSettingsService settings;
    private final EmbeddingGateway embeddingGateway;

    public KnowledgeEmbeddingService(
            AppProperties properties,
            KnowledgeSettingsService settings,
            EmbeddingGateway embeddingGateway) {
        this.properties = properties;
        this.settings = settings;
        this.embeddingGateway = embeddingGateway;
    }

    /**
     * 为 chunk 生成可持久化的向量字符串。
     *
     * <p>这里返回 Optional：embedding 没开、模型没配、供应商调用失败时都不会打断文档入库。
     * 这样知识库至少能保留关键词检索能力，避免一个外部模型故障拖垮核心功能。
     */
    public Optional<String> embedForStorage(String text) {
        if (!enabled()) {
            return Optional.empty();
        }
        String modelProfileId = embeddingModelProfileId();
        if (modelProfileId.isBlank()) {
            return Optional.empty();
        }
        try {
            var answer = embeddingGateway.embed(new EmbeddingGateway.EmbeddingRequest(
                    modelProfileId,
                    trimForEmbedding(text)));
            return Optional.of(serialize(answer.vector()));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    /**
     * 查询时生成 query embedding。失败时返回空，调用方会自动退回关键词检索。
     */
    public Optional<double[]> embedForSearch(String query) {
        return embedForStorage(query).map(KnowledgeEmbeddingService::deserialize);
    }

    public double vectorWeight() {
        double configured = properties.rag() == null ? 0 : properties.rag().vectorWeight();
        return configured <= 0 ? DEFAULT_VECTOR_WEIGHT : configured;
    }

    public static double cosineSimilarity(double[] left, double[] right) {
        if (left.length == 0 || right.length == 0 || left.length != right.length) {
            return 0;
        }
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        if (leftNorm == 0 || rightNorm == 0) {
            return 0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    public static double[] deserialize(String serialized) {
        if (serialized == null || serialized.isBlank()) {
            return new double[0];
        }
        return Arrays.stream(serialized.split(","))
                .filter(value -> !value.isBlank())
                .mapToDouble(Double::parseDouble)
                .toArray();
    }

    private boolean enabled() {
        return settings.embeddingEnabled();
    }

    private String embeddingModelProfileId() {
        return settings.embeddingModelProfileId();
    }

    private String trimForEmbedding(String text) {
        String value = text == null ? "" : text;
        int maxChars = settings.chunkSize();
        if (maxChars <= 0) {
            maxChars = DEFAULT_MAX_EMBEDDING_CHARS;
        }
        return value.length() <= maxChars ? value : value.substring(0, maxChars);
    }

    private static String serialize(List<Double> vector) {
        return vector.stream()
                // 保留 8 位小数通常足够做相似度排序，也能显著减少 H2 中的字段体积。
                .map(value -> String.format(Locale.ROOT, "%.8f", value))
                .collect(Collectors.joining(","));
    }
}
