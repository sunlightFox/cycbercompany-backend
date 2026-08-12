package io.github.yourname.agentstudio.mod;

/**
 * Extension point owned by an installed Mod. The platform only consumes the
 * manifest and does not know how the Mod implements its capabilities.
 */
public interface ModProvider {
    ModManifestView manifest();
}
