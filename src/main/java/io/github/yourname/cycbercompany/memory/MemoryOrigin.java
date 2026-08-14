package io.github.yourname.cycbercompany.memory;

/** Records whether the user explicitly created a memory or the runtime inferred it. */
public enum MemoryOrigin {
    USER_CREATED,
    AUTO_EXTRACTED,
    AUTO_MERGED
}
