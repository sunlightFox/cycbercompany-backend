package io.github.yourname.cycbercompany.mod;

public class ModInstallationRequiredException extends RuntimeException {
    private final String modId;

    public ModInstallationRequiredException(String modId) {
        super("Mod must be installed before it can be opened: " + modId);
        this.modId = modId;
    }

    public String modId() {
        return modId;
    }
}
