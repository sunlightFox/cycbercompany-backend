package io.github.yourname.cycbercompany.nodeclient.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class BoundedTextMessageAccumulatorTest {

    @Test
    void countsTheWholeFragmentedMessageInUtf8Bytes() {
        BoundedTextMessageAccumulator accumulator = new BoundedTextMessageAccumulator(4);

        var first = accumulator.append("你", false);
        var second = accumulator.append("a", true);

        assertEquals(BoundedTextMessageAccumulator.Status.INCOMPLETE, first.status());
        assertEquals(BoundedTextMessageAccumulator.Status.COMPLETE, second.status());
        assertEquals("你a", second.message());
    }

    @Test
    void rejectsOversizedMessageAndResetsForTheNextMessage() {
        BoundedTextMessageAccumulator accumulator = new BoundedTextMessageAccumulator(5);

        var rejected = accumulator.append("你你", true);
        var next = accumulator.append("ok", true);

        assertEquals(BoundedTextMessageAccumulator.Status.TOO_LARGE, rejected.status());
        assertNull(rejected.message());
        assertEquals(BoundedTextMessageAccumulator.Status.COMPLETE, next.status());
        assertEquals("ok", next.message());
    }

    @Test
    void canClearAnIncompleteMessageWhenTheConnectionEnds() {
        BoundedTextMessageAccumulator accumulator = new BoundedTextMessageAccumulator(32);

        assertEquals(
                BoundedTextMessageAccumulator.Status.INCOMPLETE,
                accumulator.append("{\"stale\":", false).status());
        accumulator.clear();

        BoundedTextMessageAccumulator.AppendResult next = accumulator.append("\"fresh\"}", true);
        assertEquals(BoundedTextMessageAccumulator.Status.COMPLETE, next.status());
        assertEquals("\"fresh\"}", next.message());
    }
}
