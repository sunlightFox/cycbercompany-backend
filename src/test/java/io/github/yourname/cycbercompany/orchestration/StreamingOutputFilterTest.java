package io.github.yourname.cycbercompany.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class StreamingOutputFilterTest {

    @Test
    void suppressesAProviderToolResultEnvelopeSplitAcrossDeltas() {
        List<String> emitted = new ArrayList<>();
        StreamingOutputFilter filter = new StreamingOutputFilter(emitted::add);

        filter.accept("Tool execution complete");
        filter.accept("d. Result: {\"status\":\"SUCCEEDED\"}");
        filter.finish();

        assertThat(emitted).isEmpty();
        assertThat(filter.emitted()).isFalse();
    }

    @Test
    void emitsOrdinaryTextBeforeTheProviderStreamCompletes() {
        List<String> emitted = new ArrayList<>();
        StreamingOutputFilter filter = new StreamingOutputFilter(emitted::add);

        filter.accept("你好，");

        assertThat(emitted).containsExactly("你好，");
        filter.finish();
        assertThat(emitted).containsExactly("你好，");
    }

    @Test
    void recognizesHiddenThinkTagWhenProviderSplitsItAcrossDeltas() {
        List<String> emitted = new ArrayList<>();
        StreamingOutputFilter filter = new StreamingOutputFilter(emitted::add);

        filter.accept("答案前<thi");
        filter.accept("nk>内部思考");
        filter.accept("</thi");
        filter.accept("nk>答案后");
        filter.finish();

        assertThat(String.join("", emitted)).isEqualTo("答案前答案后");
    }

    @Test
    void removesToolBlocksAndKeepsTextAroundThem() {
        List<String> emitted = new ArrayList<>();
        StreamingOutputFilter filter = new StreamingOutputFilter(emitted::add);

        filter.accept("开始<tool_call>{\"name\":\"shell.run\"}</tool_call>结束");
        filter.finish();

        assertThat(String.join("", emitted)).isEqualTo("开始结束");
    }

    @Test
    void dropsAnUnclosedHiddenBlockAtEndOfStream() {
        List<String> emitted = new ArrayList<>();
        StreamingOutputFilter filter = new StreamingOutputFilter(emitted::add);

        filter.accept("可见<mm:think>不应泄漏");
        filter.finish();

        assertThat(String.join("", emitted)).isEqualTo("可见");
    }

    @Test
    void dropsAnOrphanedMiniMaxThinkEndTagAndItsLineTail() {
        List<String> emitted = new ArrayList<>();
        StreamingOutputFilter filter = new StreamingOutputFilter(emitted::add);

        filter.accept("已完成\n</mm:think>");
        filter.accept("w\n可见的后续答案");
        filter.finish();

        assertThat(String.join("", emitted)).isEqualTo("已完成\n可见的后续答案");
    }

    @Test
    void recognizesAnOrphanedMiniMaxThinkEndTagSplitAcrossDeltas() {
        List<String> emitted = new ArrayList<>();
        StreamingOutputFilter filter = new StreamingOutputFilter(emitted::add);

        filter.accept("安全文本</mm:thi");
        filter.accept("nk>内部残留");
        filter.finish();

        assertThat(String.join("", emitted)).isEqualTo("安全文本");
    }

    @Test
    void doesNotLoseARealLessThanSignThatIsNotAHiddenTag() {
        List<String> emitted = new ArrayList<>();
        StreamingOutputFilter filter = new StreamingOutputFilter(emitted::add);

        filter.accept("a < b");
        filter.finish();

        assertThat(String.join("", emitted)).isEqualTo("a < b");
    }

    @Test
    void dropsMiniMaxInternalMarkerLineWithoutStoppingFollowingText() {
        List<String> emitted = new ArrayList<>();
        StreamingOutputFilter filter = new StreamingOutputFilter(emitted::add);

        filter.accept("前文\n]<]minimax[>");
        filter.accept("内部状态");
        filter.accept("\n后文");
        filter.finish();

        assertThat(String.join("", emitted)).isEqualTo("前文\n后文");
    }

    @Test
    void dropsMiniMaxMarkerWhenTheProviderEndsBeforeANewline() {
        List<String> emitted = new ArrayList<>();
        StreamingOutputFilter filter = new StreamingOutputFilter(emitted::add);

        filter.accept("答案\n]<]minimax[>");
        filter.finish();

        assertThat(String.join("", emitted)).isEqualTo("答案\n");
    }

    @Test
    void dropsOtherProviderReasoningTagsRegardlessOfTagCase() {
        List<String> emitted = new ArrayList<>();
        StreamingOutputFilter filter = new StreamingOutputFilter(emitted::add);

        filter.accept("可见<ANALYSIS>private");
        filter.accept(" reasoning</analysis>答案<THINKING>private</THINKING>结束");
        filter.finish();

        assertThat(String.join("", emitted)).isEqualTo("可见答案结束");
    }

    @Test
    void dropsAnOrphanedReasoningEndTagAndItsTail() {
        List<String> emitted = new ArrayList<>();
        StreamingOutputFilter filter = new StreamingOutputFilter(emitted::add);

        filter.accept("答案</REASONING>internal residue\n后续可见");
        filter.finish();

        assertThat(String.join("", emitted)).isEqualTo("答案后续可见");
    }
}
