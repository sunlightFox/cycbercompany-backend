package io.github.yourname.agentstudio.tool;

import io.github.yourname.agentstudio.knowledge.EvidenceBundle;
import io.github.yourname.agentstudio.knowledge.KnowledgeQueryService;
import io.github.yourname.agentstudio.knowledge.KnowledgeSearchCommand;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 控制面本地执行的低风险工具：时间、知识库检索和网页检索。 */
@Service
public class BackendToolProvider implements ToolProvider {

    public static final String PROVIDER_ID = "backend";

    private final KnowledgeQueryService knowledge;
    private final WebSearchService webSearch;

    public BackendToolProvider(KnowledgeQueryService knowledge, WebSearchService webSearch) {
        this.knowledge = knowledge;
        this.webSearch = webSearch;
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public List<ToolDescriptor> discover(ToolDiscoveryRequest request) {
        String knowledgeIds = String.join("\n", request.knowledgeBaseIds());
        return List.of(
                descriptor(
                        "local_time",
                        "Returns the current server time and time zone.",
                        objectSchema(Map.of()),
                        Map.of()),
                descriptor(
                        "knowledge_search",
                        "Searches the knowledge bases resolved for this run.",
                        objectSchema(Map.of(
                                "query", Map.of("type", "string"),
                                "limit", Map.of("type", "integer", "minimum", 1, "maximum", 10))),
                        Map.of("knowledgeBaseIds", knowledgeIds)),
                descriptor(
                        "web_search",
                        "Searches the web and returns structured, untrusted evidence.",
                        objectSchema(Map.of(
                                "query", Map.of("type", "string"),
                                "limit", Map.of("type", "integer", "minimum", 1, "maximum", 10))),
                        Map.of()));
    }

    @Override
    public ToolProviderResult invoke(ToolInvocationRequest request) {
        if (!PROVIDER_ID.equals(request.binding().providerId())) {
            throw new IllegalArgumentException("BackendToolProvider cannot invoke binding: " + request.binding().bindingId());
        }
        try {
            return switch (request.binding().providerToolName()) {
                case "local_time" -> success(Map.of(
                        "time", OffsetDateTime.now(ZoneId.systemDefault()).toString(),
                        "zone", ZoneId.systemDefault().getId()));
                case "knowledge_search" -> knowledgeSearch(request);
                case "web_search" -> webSearch(request);
                default -> throw new IllegalArgumentException(
                        "Unknown backend tool: " + request.binding().providerToolName());
            };
        } catch (Exception ex) {
            return new ToolProviderResult("FAILED", false, Map.of(), message(ex), null);
        }
    }

    private ToolProviderResult knowledgeSearch(ToolInvocationRequest request) {
        String query = requiredString(request.arguments(), "query");
        int limit = boundedLimit(request.arguments().get("limit"));
        String encodedIds = request.binding().attributes().getOrDefault("knowledgeBaseIds", "");
        List<String> knowledgeBaseIds = encodedIds.isBlank()
                ? List.of()
                : encodedIds.lines().filter(value -> !value.isBlank()).toList();
        EvidenceBundle bundle = knowledge.search(
                new KnowledgeSearchCommand(knowledgeBaseIds, query, limit),
                request.actor());
        // 保留 Evidence 的结构化字段，既方便模型阅读，也让 Run 的预检索能从同一 Provider
        // 结果恢复服务端引用，不必绕过 ToolRouter 再调用一次知识库服务。
        return success(Map.of("query", query, "matches", bundle.evidence()));
    }

    private ToolProviderResult webSearch(ToolInvocationRequest request) {
        String query = requiredString(request.arguments(), "query");
        int limit = boundedLimit(request.arguments().get("limit"));
        WebSearchResponse response = webSearch.searchDetailed(new WebSearchCommand(query, limit));
        return success(Map.of(
                "query", response.query(),
                "intent", response.intent(),
                "results", response.results(),
                "trace", response.trace()));
    }

    private static ToolDescriptor descriptor(
            String name,
            String description,
            Map<String, Object> schema,
            Map<String, String> attributes) {
        return new ToolDescriptor(
                "backend:" + name,
                name,
                PROVIDER_ID,
                name,
                description,
                RiskLevel.LOW,
                false,
                schema,
                attributes);
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties) {
        return Map.of("type", "object", "properties", properties);
    }

    private static ToolProviderResult success(Map<String, Object> result) {
        return new ToolProviderResult("SUCCEEDED", true, result, "", null);
    }

    private static String requiredString(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("Backend tool argument '" + name + "' must be a non-empty string.");
        }
        return text.trim();
    }

    private static int boundedLimit(Object value) {
        int limit = value instanceof Number number ? number.intValue() : 5;
        return Math.max(1, Math.min(limit, 10));
    }

    private static String message(Exception ex) {
        return ex.getMessage() == null || ex.getMessage().isBlank()
                ? ex.getClass().getSimpleName()
                : ex.getMessage();
    }
}
