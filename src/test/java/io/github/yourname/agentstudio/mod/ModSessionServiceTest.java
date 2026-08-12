package io.github.yourname.agentstudio.mod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.yourname.agentstudio.security.ActorContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ModSessionServiceTest {

    @Test
    void ownsSessionAndProjectsDirectPlayerCommands() {
        ModSessionService service = new ModSessionService();
        ActorContext actor = ActorContext.local();
        ModSessionView opened = service.open(
                new OpenModSessionCommand("video-player", null, "docked", "doraemon-1", "Doraemon"), actor);

        assertEquals("ACTIVE", opened.status());
        assertEquals("main", opened.surfaces().getFirst().surfaceId());
        assertEquals("doraemon-1", opened.surfaces().getFirst().state().get("resourceId"));

        ModSessionView paused = service.command(opened.sessionId(),
                new ModCommand("pause", Map.of("positionMs", 1234L)), actor);
        assertEquals("pause", paused.context().get("lastCommand"));
        assertEquals(1234L, paused.surfaces().getFirst().state().get("positionMs"));

        ModSessionView closed = service.command(opened.sessionId(), new ModCommand("close", Map.of()), actor);
        assertEquals("SUSPENDED", closed.status());
        assertTrue(closed.surfaces().stream().noneMatch(ModSurfaceView::active));
    }

    @Test
    void ignoresNullOptionalArgumentsFromModUi() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("mediaId", "doraemon-1");
        raw.put("sourceId", null);
        ModCommand command = new ModCommand("open", raw);

        assertEquals(Map.of("mediaId", "doraemon-1"), command.arguments());
    }

    @Test
    void delegatesResourceStateWithoutKnowingTheModSchema() {
        AtomicReference<Map<String, Object>> saved = new AtomicReference<>();
        ModStateStore store = new ModStateStore() {
            @Override
            public Map<String, Object> load(String modId, String resourceId, ActorContext actor) {
                return Map.of("restoredValue", "from-mod");
            }

            @Override
            public void save(String modId, String resourceId, String event, Map<String, Object> state,
                             ActorContext actor) {
                saved.set(state);
            }
        };
        ModSessionService service = new ModSessionService(null, List.of(store));
        ActorContext actor = ActorContext.local();
        ModSessionView opened = service.open(
                new OpenModSessionCommand("custom-mod", "surface", "docked", "resource-1", "Demo"), actor);

        assertEquals("from-mod", opened.surfaces().getFirst().state().get("restoredValue"));
        service.command(opened.sessionId(), new ModCommand("update", Map.of("value", 7)), actor);
        assertEquals(7, saved.get().get("value"));
    }
}
