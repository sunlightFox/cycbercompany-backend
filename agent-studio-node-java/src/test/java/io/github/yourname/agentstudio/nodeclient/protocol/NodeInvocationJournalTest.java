package io.github.yourname.agentstudio.nodeclient.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NodeInvocationJournalTest {

    @TempDir
    Path tempDir;

    @Test
    void duplicateInvocationReturnsCachedTerminalResultInsteadOfRunningAgain() {
        NodeInvocationJournal journal = new NodeInvocationJournal(new ObjectMapper(), tempDir);

        NodeInvocationJournal.Acceptance first = journal.accept("nodeinv-1", "fs.write", "sha256:args", 1);
        journal.start("nodeinv-1");
        journal.finish("nodeinv-1", "SUCCEEDED", Map.of("written", true), null);
        NodeInvocationJournal.Acceptance retry = journal.accept("nodeinv-1", "fs.write", "sha256:args", 1);

        assertEquals(NodeInvocationJournal.Decision.NEW, first.decision());
        assertEquals(NodeInvocationJournal.Decision.CACHED_TERMINAL, retry.decision());
        assertEquals("SUCCEEDED", retry.entry().status());
        assertEquals(true, retry.entry().result().get("written"));
    }

    @Test
    void rejectsDuplicateIdWhenToolOrArgumentsDoNotMatch() {
        NodeInvocationJournal journal = new NodeInvocationJournal(new ObjectMapper(), tempDir);
        journal.accept("nodeinv-1", "fs.write", "sha256:args-a", 1);

        NodeInvocationJournal.Acceptance conflicting = journal.accept("nodeinv-1", "shell.run", "sha256:args-b", 1);

        assertEquals(NodeInvocationJournal.Decision.CONFLICT, conflicting.decision());
    }

    @Test
    void recoversAnInProgressInvocationAsUnknownAfterNodeRestart() {
        NodeInvocationJournal firstProcess = new NodeInvocationJournal(new ObjectMapper(), tempDir);
        firstProcess.accept("nodeinv-1", "fs.write", "sha256:args", 1);
        firstProcess.start("nodeinv-1");

        NodeInvocationJournal afterRestart = new NodeInvocationJournal(new ObjectMapper(), tempDir);

        assertEquals("UNKNOWN", afterRestart.find("nodeinv-1").status());
        assertTrue(afterRestart.find("nodeinv-1").errorMessage().contains("restarted"));
        assertTrue(Files.exists(tempDir.resolve("invocation-journal.json")));
    }

    @Test
    void cancelAcknowledgementDoesNotPretendToRollbackTheEffect() {
        NodeInvocationJournal journal = new NodeInvocationJournal(new ObjectMapper(), tempDir);
        journal.accept("nodeinv-1", "shell.run", "sha256:args", 1);

        NodeJournalEntry entry = journal.cancelRequested("nodeinv-1");

        assertEquals("CANCEL_REQUESTED", entry.status());
        assertFalse(entry.terminal());
    }
}
