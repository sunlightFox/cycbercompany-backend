package io.github.yourname.agentstudio.agent;

import java.util.List;

public record AgentDraftTestView(
        String agentId,
        String versionId,
        String manifestDigest,
        String modelProfileId,
        String content,
        Integer promptTokens,
        Integer completionTokens,
        String rawModel,
        String finishReason,
        boolean toolCallsBlocked,
        List<String> notices) {

    public AgentDraftTestView {
        content = content == null ? "" : content;
        notices = notices == null ? List.of() : List.copyOf(notices);
    }
}
