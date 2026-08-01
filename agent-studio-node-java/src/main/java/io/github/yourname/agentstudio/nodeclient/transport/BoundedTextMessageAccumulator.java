package io.github.yourname.agentstudio.nodeclient.transport;

/**
 * 按 UTF-8 字节预算累加 WebSocket 文本分片。
 *
 * <p>{@link java.net.http.WebSocket.Listener#onText} 可能把一条消息拆成多次回调。如果只限制
 * 单个分片，攻击者仍可用许多小分片构造超大 JSON。本类在 JSON 解析前限制整条消息。
 */
final class BoundedTextMessageAccumulator {

    enum Status {
        INCOMPLETE,
        COMPLETE,
        TOO_LARGE
    }

    record AppendResult(Status status, String message) {
    }

    private final int maxBytes;
    private final StringBuilder buffer = new StringBuilder();
    private int bufferedBytes;

    BoundedTextMessageAccumulator(int maxBytes) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive.");
        }
        this.maxBytes = maxBytes;
    }

    synchronized AppendResult append(CharSequence fragment, boolean last) {
        CharSequence safeFragment = fragment == null ? "" : fragment;
        long nextSize = (long) bufferedBytes + utf8Length(safeFragment);
        if (nextSize > maxBytes) {
            reset();
            return new AppendResult(Status.TOO_LARGE, null);
        }

        buffer.append(safeFragment);
        bufferedBytes = (int) nextSize;
        if (!last) {
            return new AppendResult(Status.INCOMPLETE, null);
        }

        String message = buffer.toString();
        reset();
        return new AppendResult(Status.COMPLETE, message);
    }

    private void reset() {
        buffer.setLength(0);
        bufferedBytes = 0;
    }

    /** 不创建临时 byte[]，避免在拒绝超大分片前再额外复制一次完整内容。 */
    static int utf8Length(CharSequence value) {
        int bytes = 0;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current <= 0x7f) {
                bytes++;
            } else if (current <= 0x7ff) {
                bytes += 2;
            } else if (Character.isHighSurrogate(current)
                    && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) {
                bytes += 4;
                index++;
            } else {
                bytes += 3;
            }
        }
        return bytes;
    }
}
