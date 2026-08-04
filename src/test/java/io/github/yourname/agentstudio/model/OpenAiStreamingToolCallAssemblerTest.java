package io.github.yourname.agentstudio.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class OpenAiStreamingToolCallAssemblerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void joinsFunctionArgumentsAcrossSeveralSseDeltas() throws Exception {
        OpenAiStreamingToolCallAssembler assembler = new OpenAiStreamingToolCallAssembler();

        assembler.accept(toolCallDelta(0, "call_read", "node_fs_", "{\"path\":\"src/"));
        assembler.accept(toolCallDelta(0, null, "read", "Main.java\",\"limit\":"));
        assembler.accept(toolCallDelta(0, null, null, "120}"));

        var calls = assembler.complete(objectMapper);

        assertThat(calls).singleElement().satisfies(call -> {
            assertThat(call.id()).isEqualTo("call_read");
            assertThat(call.name()).isEqualTo("node_fs_read");
            assertThat(call.arguments()).containsEntry("path", "src/Main.java").containsEntry("limit", 120);
        });
    }

    @Test
    void keepsSeparateCallsInTheirProviderIndexes() throws Exception {
        OpenAiStreamingToolCallAssembler assembler = new OpenAiStreamingToolCallAssembler();

        assembler.accept(toolCallDeltas(
                toolCallFragment(1, "call_second", "second", "{}"),
                toolCallFragment(0, "call_first", "first", "{}")));

        assertThat(assembler.complete(objectMapper))
                .extracting(ModelGateway.ModelToolCall::id)
                .containsExactly("call_first", "call_second");
    }

    @Test
    void rejectsUnfinishedArgumentsInsteadOfExecutingThem() throws Exception {
        OpenAiStreamingToolCallAssembler assembler = new OpenAiStreamingToolCallAssembler();
        assembler.accept(toolCallDelta(0, "call_partial", "fs_write", "{\"path\":\""));

        assertThatThrownBy(() -> assembler.complete(objectMapper))
                .isInstanceOf(ModelGatewayException.class)
                .hasMessageContaining("invalid streamed tool arguments");
    }

    /** 直接构造供应商 delta，避免测试因嵌套 JSON 字符串转义而失去可读性。 */
    private JsonNode toolCallDelta(int index, String id, String name, String arguments) {
        return toolCallDeltas(toolCallFragment(index, id, name, arguments));
    }

    private JsonNode toolCallDeltas(JsonNode... fragments) {
        var array = objectMapper.createArrayNode();
        for (JsonNode fragment : fragments) {
            array.add(fragment);
        }
        return array;
    }

    private JsonNode toolCallFragment(int index, String id, String name, String arguments) {
        var fragment = objectMapper.createObjectNode().put("index", index);
        if (id != null) {
            fragment.put("id", id);
        }
        if (name != null || arguments != null) {
            var function = fragment.putObject("function");
            if (name != null) {
                function.put("name", name);
            }
            if (arguments != null) {
                function.put("arguments", arguments);
            }
        }
        return fragment;
    }
}
