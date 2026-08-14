package io.github.yourname.cycbercompany.tool;

import java.time.Instant;

/** Extracted page text paired with the result that produced it. */
public record WebEvidence(
        String pageTitle,
        String excerpt,
        boolean readable,
        boolean relevant,
        String verification,
        Instant publishedAt) {

    public WebEvidence(
            String pageTitle,
            String excerpt,
            boolean readable,
            boolean relevant,
            String verification) {
        this(pageTitle, excerpt, readable, relevant, verification, null);
    }

    public static WebEvidence unreadable(String verification) {
        return new WebEvidence("", "", false, false, verification, null);
    }
}
