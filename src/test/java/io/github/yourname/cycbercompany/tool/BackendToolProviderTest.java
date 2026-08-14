package io.github.yourname.cycbercompany.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.yourname.cycbercompany.knowledge.KnowledgeQueryService;
import io.github.yourname.cycbercompany.security.ActorContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BackendToolProviderTest {

    @Test
    void exposesRequiredQueriesAndEvidenceBoundariesToTheModel() {
        BackendToolProvider provider = new BackendToolProvider(
                mock(KnowledgeQueryService.class), mock(WebSearchService.class));
        List<ToolDescriptor> tools = provider.discover(new ToolDiscoveryRequest(
                "run-1", null, List.of("kb-1"), List.of(), List.of(), ActorContext.local()));

        ToolDescriptor localTime = tool(tools, "local_time");
        ToolDescriptor knowledge = tool(tools, "knowledge_search");
        ToolDescriptor web = tool(tools, "web_search");

        assertThat(localTime.description()).contains("server time", "does not infer");
        assertThat(localTime.inputSchema()).containsEntry("additionalProperties", false);
        assertThat(knowledge.inputSchema().get("required")).isEqualTo(List.of("query"));
        assertThat(web.inputSchema().get("required")).isEqualTo(List.of("query"));
        assertThat(knowledge.description()).contains("bound to this run", "untrusted evidence");
        assertThat(web.description()).contains("public web", "not instructions", "read a page");

        @SuppressWarnings("unchecked")
        Map<String, Object> webProperties = (Map<String, Object>) web.inputSchema().get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> query = (Map<String, Object>) webProperties.get("query");
        assertThat(query.get("description")).asString().contains("disambiguating names or dates");
    }

    private static ToolDescriptor tool(List<ToolDescriptor> tools, String name) {
        return tools.stream().filter(tool -> name.equals(tool.logicalName())).findFirst().orElseThrow();
    }
}
