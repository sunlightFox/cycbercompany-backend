package io.github.yourname.cycbercompany.orchestration;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 对模型流式输出做增量过滤。
 *
 * <p>模型供应商返回的内容不是天然可信的用户文本。思考块和工具块可能跨越多个
 * SSE delta，如果每个 delta 单独做字符串替换，就会因为标签被拆开而把内部内容
 * 误发给前端。因此这里保存一个很小的尾部缓冲区，并用状态机跨 delta 识别标签。
 *
 * <p>这个类只负责实时输出，不负责最终答案的格式化。调用方仍然应该对完整模型响应
 * 再执行一次最终清理，确保持久化内容和实时内容遵循同一套安全规则。
 */
final class StreamingOutputFilter {

    private static final String RAW_TOOL_RESULT_PREFIX = "Tool execution completed. Result: {";
    private static final List<Block> BLOCKS = List.of(
            new Block("<think>", "</think>"),
            new Block("<mm:think>", "</mm:think>"),
            // Different OpenAI-compatible providers use these synonymous tags.
            // They are internal reasoning just like <think>, never answer text.
            new Block("<thinking>", "</thinking>"),
            new Block("<analysis>", "</analysis>"),
            new Block("<reasoning>", "</reasoning>"),
            new Block("<tool_call>", "</tool_call>"),
            new Block("<tool_result>", "</tool_result>"),
            // MiniMax occasionally emits this internal marker as a standalone line.
            // Treat the rest of that line as non-user-facing control output.
            new Block("]<]minimax[>", "\n"));

    private final Consumer<String> onSafeText;
    private final StringBuilder pending = new StringBuilder();
    private Block hiddenBlock;
    private boolean discardLineAfterOrphanedThinkEnd;
    private boolean openingChecked;
    private boolean discardRawToolResult;
    private boolean emitted;

    StreamingOutputFilter(Consumer<String> onSafeText) {
        this.onSafeText = Objects.requireNonNull(onSafeText, "onSafeText");
    }

    /** 接收一个供应商 delta，并尽可能立刻发出已经确认安全的普通文本。 */
    void accept(String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        pending.append(delta);
        drain(false);
    }

    /**
     * 流结束时刷新尾部缓冲区。
     *
     * <p>普通文本的尾部不再可能等到一个完整标签，因此可以发出；隐藏块的尾部必须
     * 丢弃，避免模型没有发送结束标签时把思考内容泄漏出去。
     */
    void finish() {
        drain(true);
        pending.setLength(0);
    }

    boolean emitted() {
        return emitted;
    }

    private void drain(boolean endOfStream) {
        while (true) {
            if (discardRawToolResult) {
                pending.setLength(0);
                return;
            }
            if (!openingChecked) {
                if (pending.length() < RAW_TOOL_RESULT_PREFIX.length()
                        && RAW_TOOL_RESULT_PREFIX.startsWith(pending.toString())) {
                    if (endOfStream) {
                        openingChecked = true;
                    } else {
                        return;
                    }
                } else {
                    openingChecked = true;
                    if (pending.toString().startsWith(RAW_TOOL_RESULT_PREFIX)) {
                        discardRawToolResult = true;
                        pending.setLength(0);
                        return;
                    }
                }
            }
            if (discardLineAfterOrphanedThinkEnd) {
                int newline = firstLineBreak();
                if (newline < 0) {
                    if (endOfStream) {
                        pending.setLength(0);
                    }
                    return;
                }
                pending.delete(0, newline + 1);
                discardLineAfterOrphanedThinkEnd = false;
                continue;
            }
            if (hiddenBlock != null) {
                int end = indexOfIgnoreCase(hiddenBlock.endTag());
                if (end < 0) {
                    // 只保留可能是结束标签前缀的尾巴，其余内容全部属于隐藏块。
                    retainSuffixPrefix(hiddenBlock.endTag(), endOfStream);
                    return;
                }
                pending.delete(0, end + hiddenBlock.endTag().length());
                hiddenBlock = null;
                continue;
            }

            int orphanedThinkEnd = firstOrphanedThinkEndTag();
            Match start = firstStartTag();
            if (orphanedThinkEnd >= 0 && (start == null || orphanedThinkEnd < start.index())) {
                if (orphanedThinkEnd > 0) {
                    emit(pending.substring(0, orphanedThinkEnd));
                    pending.delete(0, orphanedThinkEnd);
                }
                if (startsWithIgnoreCase("</mm:think>")) {
                    pending.delete(0, "</mm:think>".length());
                } else if (startsWithIgnoreCase("</thinking>")) {
                    pending.delete(0, "</thinking>".length());
                } else if (startsWithIgnoreCase("</analysis>")) {
                    pending.delete(0, "</analysis>".length());
                } else if (startsWithIgnoreCase("</reasoning>")) {
                    pending.delete(0, "</reasoning>".length());
                } else {
                    pending.delete(0, "</think>".length());
                }
                // A closing tag without a matching opening tag is provider control
                // output. Treat the line tail as unsafe rather than leaking a split
                // reasoning fragment such as "</mm:think>\nw" to the client.
                discardLineAfterOrphanedThinkEnd = true;
                continue;
            }
            if (start == null) {
                int safeLength = endOfStream ? pending.length() : pending.length() - possibleTagPrefixLength();
                if (safeLength > 0) {
                    emit(pending.substring(0, safeLength));
                    pending.delete(0, safeLength);
                }
                return;
            }

            if (start.index() > 0) {
                emit(pending.substring(0, start.index()));
                pending.delete(0, start.index());
            }
            pending.delete(0, start.block().startTag().length());
            hiddenBlock = start.block();
        }
    }

    private int firstOrphanedThinkEndTag() {
        int result = -1;
        for (String endTag : List.of("</think>", "</mm:think>", "</thinking>", "</analysis>", "</reasoning>")) {
            int index = indexOfIgnoreCase(endTag);
            if (index >= 0 && (result < 0 || index < result)) {
                result = index;
            }
        }
        return result;
    }

    private int firstLineBreak() {
        for (int index = 0; index < pending.length(); index++) {
            char current = pending.charAt(index);
            if (current == '\n' || current == '\r') {
                if (current == '\r' && index + 1 < pending.length() && pending.charAt(index + 1) == '\n') {
                    return index + 1;
                }
                return index;
            }
        }
        return -1;
    }

    private Match firstStartTag() {
        Match result = null;
        for (Block block : BLOCKS) {
            int index = indexOfIgnoreCase(block.startTag());
            if (index >= 0 && (result == null || index < result.index())) {
                result = new Match(index, block);
            }
        }
        return result;
    }

    private int possibleTagPrefixLength() {
        int longest = 0;
        for (Block block : BLOCKS) {
            longest = Math.max(longest, suffixPrefixLength(block.startTag()));
            longest = Math.max(longest, suffixPrefixLength(block.endTag()));
        }
        return longest;
    }

    private void retainSuffixPrefix(String tag, boolean endOfStream) {
        if (endOfStream) {
            pending.setLength(0);
            return;
        }
        int keep = suffixPrefixLength(tag);
        if (keep == 0) {
            pending.setLength(0);
        } else {
            pending.delete(0, pending.length() - keep);
        }
    }

    private int suffixPrefixLength(String tag) {
        int maximum = Math.min(tag.length() - 1, pending.length());
        for (int length = maximum; length > 0; length--) {
            int start = pending.length() - length;
            if (matchesIgnoreCase(start, tag, length)) {
                return length;
            }
        }
        return 0;
    }

    private int indexOfIgnoreCase(String needle) {
        int maximum = pending.length() - needle.length();
        for (int index = 0; index <= maximum; index++) {
            if (matchesIgnoreCase(index, needle, needle.length())) {
                return index;
            }
        }
        return -1;
    }

    private boolean startsWithIgnoreCase(String prefix) {
        return pending.length() >= prefix.length() && matchesIgnoreCase(0, prefix, prefix.length());
    }

    private boolean matchesIgnoreCase(int pendingOffset, String value, int length) {
        if (pendingOffset < 0 || pendingOffset + length > pending.length()) {
            return false;
        }
        for (int index = 0; index < length; index++) {
            if (Character.toLowerCase(pending.charAt(pendingOffset + index))
                    != Character.toLowerCase(value.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private void emit(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        emitted = true;
        onSafeText.accept(text);
    }

    private record Block(String startTag, String endTag) {
    }

    private record Match(int index, Block block) {
    }
}
