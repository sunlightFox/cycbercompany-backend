package io.github.yourname.agentstudio.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yourname.agentstudio.knowledge.EvidenceBundle;
import io.github.yourname.agentstudio.tool.WebEvidence;
import io.github.yourname.agentstudio.tool.WebSearchResult;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RunCommandSystemPromptTest {

    @Test
    void queryCompactionRetainsChineseContextInMixedLanguageRequests() {
        String query = RunCommandService.webSearchQuery("搜索今天 OpenAI 发布的新闻，并带来源链接");

        assertThat(query).contains("今天", "OpenAI", "新闻").doesNotContain("搜索", "来源链接");
    }

    @Test
    void queryCompactionKeepsEnglishFreshnessAndNewsIntent() {
        String query = RunCommandService.webSearchQuery("Please search today's latest food news with source links");

        assertThat(query).contains("today", "latest", "food", "news")
                .doesNotContain("Please", "search", "source links");
    }

    @Test
    void codingRunsReceiveWorkspaceScopeAndVerificationWorkflow() {
        CreateRunCommand command = new CreateRunCommand(
                "conversation-1",
                "Create a project in task-board.",
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "node-1",
                "task-board");

        String prompt = RunCommandService.buildSystemPrompt(
                "You are a coding assistant.",
                command,
                new EvidenceBundle(List.of()),
                List.of(),
                List.of(),
                "",
                "");

        assertThat(prompt)
                .contains("target directory")
                .contains("only project scope")
                .contains("unrelated samples")
                .contains("project.inspect")
                .contains("project.discover")
                .contains("project.map")
                .contains("manifest-backed recommendations")
                .contains("managed development process")
                .contains("failedTests")
                .contains("sourceLocations")
                .contains("repeat the same check")
                .contains("fs.search")
                .contains("startLine")
                .contains("finite tool budget")
                .contains("Project scope for this run: task-board");
    }

    @Test
    void webPromptDistinguishesVerifiedPageTextFromSearchSnippets() {
        WebSearchResult result = new WebSearchResult(
                "Spring reference",
                "https://docs.spring.io/reference",
                "Search result snippet",
                "searxng",
                "SEARXNG",
                Instant.parse("2026-07-31T12:00:00Z"),
                new WebEvidence("Spring reference", "Verified reference excerpt", true, true, "Readable page text matched the query."));

        String prompt = RunCommandService.buildSystemPrompt(
                "You are a research assistant.",
                new CreateRunCommand("conversation-1", "Search Spring documentation", null, null,
                        List.of(), List.of(), List.of(), List.of(), null, null),
                new EvidenceBundle(List.of()),
                List.of(result),
                List.of(),
                "Spring documentation",
                "");

        assertThat(prompt)
                .contains("verified excerpt: Verified reference excerpt")
                .contains("published at: 2026-07-31T12:00:00Z")
                .contains("Use verified page excerpts for factual claims")
                .contains("https://docs.spring.io/reference");
    }

    @Test
    void currentSearchLimitationPromptRequiresACompletedAnswer() {
        String prompt = RunCommandService.buildSystemPrompt(
                "You are a research assistant.",
                new CreateRunCommand("conversation-1", "Search today's food news", null, null,
                        List.of(), List.of(), List.of(), List.of(), null, null),
                new EvidenceBundle(List.of()),
                List.of(),
                List.of(),
                "food news",
                "Search candidates were found, but none had a verifiable publication time within the requested current-news window.");

        assertThat(prompt)
                .contains("state the precise retrieval limitation in the final answer")
                .contains("Do not say that you will search");
    }

    @Test
    void selectedSkillInstructionsAppearBeforeUntrustedRetrievedEvidence() {
        CreateRunCommand command = new CreateRunCommand(
                "conversation-1", "Review this code", "model-1", "agent-1",
                List.of("kb-1"), List.of("review-skill"), List.of(), List.of(), null, null);
        EvidenceBundle evidence = new EvidenceBundle(List.of(
                new EvidenceBundle.Evidence(1L, "doc-1", "kb-1", "doc", 0, "External evidence text", 1.0)));

        String prompt = RunCommandService.buildSystemPrompt(
                "Agent system instruction",
                command,
                evidence,
                List.of(),
                List.of(),
                "",
                "",
                "完整 Skill 指令：先检查测试，再检查兼容性。");

        assertThat(prompt).contains("完整 Skill 指令：先检查测试，再检查兼容性。");
        assertThat(prompt.indexOf("完整 Skill 指令"))
                .isLessThan(prompt.indexOf("External evidence text"));
    }

    @Test
    void knowledgeEvidenceDoesNotImplyThatTheSourceDocumentIsTruncated() {
        EvidenceBundle evidence = new EvidenceBundle(List.of(
                new EvidenceBundle.Evidence(1L, "doc-1", "kb-1", "resume.pdf", 0, "Born in 1993.", 1.0)));

        String prompt = RunCommandService.buildSystemPrompt(
                "Answer from evidence.",
                new CreateRunCommand("conversation-1", "How old is this person?", null, null,
                        List.of("kb-1"), List.of(), List.of(), List.of(), null, null),
                evidence,
                List.of(),
                List.of(),
                "",
                "");

        assertThat(prompt)
                .contains("Do not infer that a source document is missing, incomplete, or truncated")
                .contains("Do not ask the user to upload or provide the complete document")
                .contains("Born in 1993.");
    }
}
