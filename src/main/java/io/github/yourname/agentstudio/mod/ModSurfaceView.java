package io.github.yourname.agentstudio.mod;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Describes a mounted Mod surface without exposing implementation details. */
public record ModSurfaceView(
        String surfaceId,
        String modId,
        String role,
        String presentation,
        boolean active,
        Map<String, Object> state,
        List<String> commands,
        Instant updatedAt) {
}
