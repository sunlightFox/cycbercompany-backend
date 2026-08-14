package io.github.yourname.cycbercompany.media;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class MediaRuntimeProcessServiceTest {
    @Test
    void doesNotStartWhenNoCommandIsConfigured() {
        MediaRuntimeProcessService service = new MediaRuntimeProcessService(new ObjectMapper(), "", "");

        assertFalse(service.configured());
        assertFalse(service.ensureStarted());
    }

    @Test
    void rejectsMalformedCommandInsteadOfUsingAShell() {
        MediaRuntimeProcessService service = new MediaRuntimeProcessService(new ObjectMapper(), "not-json", "");

        assertTrue(service.configured());
        assertFalse(service.ensureStarted());
    }
}
