package io.github.yourname.agentstudio.media;

/** Safe runtime availability signal for the Mod UI; endpoint details stay server-side. */
public record MediaRuntimeStatusView(String status, String message) {
}
