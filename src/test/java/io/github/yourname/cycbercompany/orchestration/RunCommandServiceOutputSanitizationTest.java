package io.github.yourname.cycbercompany.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yourname.cycbercompany.model.ModelGateway;
import java.util.List;
import org.junit.jupiter.api.Test;

class RunCommandServiceOutputSanitizationTest {

    @Test
    void removesAnOrphanedMiniMaxReasoningEndTagButKeepsTheAnswer() {
        assertThat(RunCommandService.sanitizeModelOutput("</mm:think>## Verified answer"))
                .isEqualTo("## Verified answer");
    }

    @Test
    void removesACompleteReasoningBlock() {
        assertThat(RunCommandService.sanitizeModelOutput("Visible<think>private</think> answer"))
                .isEqualTo("Visible answer");
    }

    @Test
    void rejectsMiniMaxControlFramesAtTheSharedFinalAnswerBoundary() {
        assertThat(RunCommandService.sanitizeModelOutput("]<]minimax[><tool_call><invoke name=\"web_search\"/>"))
                .isBlank();
    }

    @Test
    void preservesAnOfficialRepositoryFromTheToolTranscript() {
        String answer = RunCommandService.appendPrimarySourceLinks(
                "这是检索结论。",
                "DeepSeek Harness 是什么？请给出开源地址或官网。",
                List.of(ModelGateway.ModelMessage.toolResult("call-1", """
                        {"results":[{"url":"https://github.com/deepseek-ai/deepseek-harness"},
                        {"url":"https://deepseek.com/harness"}]}
                        """)));

        assertThat(answer).contains("https://github.com/deepseek-ai/deepseek-harness");
        assertThat(answer).contains("https://deepseek.com/harness");
    }
}
