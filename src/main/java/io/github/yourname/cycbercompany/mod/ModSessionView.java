package io.github.yourname.cycbercompany.mod;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ModSessionView(
        String sessionId,
        String modId,
        String status,
        List<ModSurfaceView> surfaces,
        Map<String, Object> context,
        Instant updatedAt) {
}
