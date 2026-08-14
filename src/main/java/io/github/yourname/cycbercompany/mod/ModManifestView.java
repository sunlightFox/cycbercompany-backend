package io.github.yourname.cycbercompany.mod;

import java.util.List;

/** Public contract shown to the workshop and used by Agent routing. */
public record ModManifestView(
        String id,
        String name,
        String version,
        String description,
        String category,
        boolean installed,
        List<ModSurfaceDeclaration> surfaces,
        List<ModCapabilityDeclaration> capabilities,
        List<String> memorySchemas,
        List<String> permissions,
        List<String> sourceAdapters) {
}
