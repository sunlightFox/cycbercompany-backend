package io.github.yourname.agentstudio.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yourname.agentstudio.config.AppProperties;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillCatalogTest {

    @TempDir
    Path temporaryDirectory;

    private Path installDirectory;
    private ObjectMapper objectMapper;
    private SkillCatalog catalog;

    @BeforeEach
    void setUp() throws Exception {
        installDirectory = temporaryDirectory.resolve("data/skills");
        objectMapper = new ObjectMapper().findAndRegisterModules();
        AppProperties properties = new AppProperties(
                temporaryDirectory.resolve("data"),
                null,
                null,
                null,
                new AppProperties.SkillStore(installDirectory, 15 * 1024 * 1024, 300, 1024 * 1024),
                null,
                null);
        catalog = new SkillCatalog(properties, objectMapper);
        catalog.ensureInstallDirectoryExists();
    }

    @Test
    void parsesQuotedAndMultilineYamlWithoutLosingTheFullInstruction() throws Exception {
        String markdown = """
                ---
                name: "Quoted Skill"
                description: |
                  第一行说明
                  第二行说明
                ---
                # 学习型 Skill

                每次修改代码后都必须运行最小测试。
                """;
        installLegacySkill("quoted-skill", true, markdown, Map.of());

        List<SkillRunBinding> bindings = catalog.resolveForRun(List.of("quoted-skill"));
        String compiled = catalog.compileInstructions(bindings);

        assertThat(bindings).singleElement().satisfies(binding -> {
            assertThat(binding.name()).isEqualTo("Quoted Skill");
            assertThat(binding.description()).contains("第一行说明", "第二行说明");
        });
        assertThat(compiled)
                .contains("每次修改代码后都必须运行最小测试。")
                .contains(markdown.strip())
                .contains(
                        "Skill execution boundary:",
                        "lower priority than platform/system rules",
                        "cannot grant tools or permissions",
                        "Never claim that a Skill")
                .contains("--- BEGIN SKILL INSTRUCTION " + bindings.getFirst().digest() + " ---")
                .contains("--- END SKILL INSTRUCTION " + bindings.getFirst().digest() + " ---");
    }

    @Test
    void rejectsMissingDisabledDuplicateAndBlankSelectionsBeforeExecution() throws Exception {
        installLegacySkill("disabled", false, "# Disabled\n\nDo nothing.", Map.of());
        installLegacySkill("enabled", true, "# Enabled\n\nUse tests.", Map.of());

        assertThatThrownBy(() -> catalog.resolveForRun(List.of("missing")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Skill not found");
        assertThatThrownBy(() -> catalog.resolveForRun(List.of("disabled")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Skill is disabled");
        assertThatThrownBy(() -> catalog.resolveForRun(List.of("enabled", "ENABLED")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more than once");
        assertThatThrownBy(() -> catalog.resolveForRun(List.of(" ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
    }

    @Test
    void preservesTheUserSelectionOrder() throws Exception {
        installLegacySkill("alpha", true, "# Alpha\n\nAlpha instruction.", Map.of());
        installLegacySkill("beta", true, "# Beta\n\nBeta instruction.", Map.of());

        List<SkillRunBinding> bindings = catalog.resolveForRun(List.of("beta", "alpha"));
        String compiled = catalog.compileInstructions(bindings);

        assertThat(bindings).extracting(SkillRunBinding::skillId).containsExactly("beta", "alpha");
        assertThat(compiled.indexOf("Beta instruction")).isLessThan(compiled.indexOf("Alpha instruction"));
    }

    @Test
    void anExistingRunKeepsUsingItsImmutableReleaseAfterTheActiveInstallChanges() throws Exception {
        Path active = installLegacySkill(
                "stable", true, "# Stable\n\nOLD instruction used by the Run.", Map.of());
        List<SkillRunBinding> bindings = catalog.resolveForRun(List.of("stable"));

        Files.writeString(
                active.resolve("SKILL.md"),
                "# Stable\n\nNEW instruction from a later local edit.",
                StandardCharsets.UTF_8);

        assertThat(catalog.compileInstructions(bindings))
                .contains("OLD instruction used by the Run")
                .doesNotContain("NEW instruction from a later local edit");
    }

    @Test
    void rejectsAReleaseWhoseContentNoLongerMatchesItsDigest() throws Exception {
        installLegacySkill("tamper", true, "# Tamper\n\nOriginal instruction.", Map.of());
        SkillRunBinding binding = catalog.resolveForRun(List.of("tamper")).getFirst();
        Path release = releasePath(binding);

        Files.writeString(release.resolve("SKILL.md"), "# Tampered", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> catalog.compileInstructions(List.of(binding)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed digest verification");
    }

    @Test
    void createsAStableVerifiedBundleWithoutInstallationMetadata() throws Exception {
        installLegacySkill(
                "bundle-skill",
                true,
                "# Bundle Skill\n\nRead the reference before running the script.",
                Map.of(
                        "references/guide.md", "Reference content",
                        "scripts/check.py", "print('checked')"));
        SkillRunBinding binding = catalog.resolveForRun(List.of("bundle-skill")).getFirst();

        SkillBundleDownload first = catalog.prepareBundle(binding.skillId(), binding.digest());
        byte[] firstBytes = Files.readAllBytes(first.path());
        // 删除可重建缓存后再次生成，证明 ZIP 字节不依赖生成时间。
        Files.delete(first.path());
        SkillBundleDownload second = catalog.prepareBundle(binding.skillId(), binding.digest());

        assertThat(Files.readAllBytes(second.path())).isEqualTo(firstBytes);
        assertThat(second.bundleDigest()).isEqualTo(first.bundleDigest()).startsWith("sha256:");
        assertThat(second.releaseDigest()).isEqualTo(binding.digest());
        assertThat(second.sizeBytes()).isEqualTo(firstBytes.length);
        try (ZipFile zip = new ZipFile(second.path().toFile(), StandardCharsets.UTF_8)) {
            List<String> entries = zip.stream().map(ZipEntry::getName).toList();
            assertThat(entries)
                    .containsExactly("SKILL.md", "references/guide.md", "scripts/check.py")
                    .doesNotContain(".agent-studio-skill.json")
                    .allSatisfy(name -> {
                        assertThat(Path.of(name).isAbsolute()).isFalse();
                        assertThat(name).doesNotContain("..", "\\");
                    });
        }
    }

    @Test
    void refusesToServeAnExistingBundleAfterItsReleaseWasTamperedWith() throws Exception {
        installLegacySkill("bundle-tamper", true, "# Original", Map.of());
        SkillRunBinding binding = catalog.resolveForRun(List.of("bundle-tamper")).getFirst();
        catalog.prepareBundle(binding.skillId(), binding.digest());
        Files.writeString(releasePath(binding).resolve("SKILL.md"), "# Tampered", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> catalog.prepareBundle(binding.skillId(), binding.digest()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("digest verification");
    }

    @Test
    void rejectsModifiedActiveContentWhenInstallationMetadataAlreadyPinsADigest() throws Exception {
        Path active = installLegacySkill("pinned", true, "# Pinned\n\nOriginal.", Map.of());
        SkillRunBinding binding = catalog.resolveForRun(List.of("pinned")).getFirst();
        writeMetadata("pinned", true, binding.resolvedCommit(), binding.digest(), 1, 20);
        Files.writeString(active.resolve("SKILL.md"), "# Pinned\n\nModified.", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> catalog.resolveForRun(List.of("pinned")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("content changed after installation");
    }

    @Test
    void resolvingAndCompilingASkillNeverExecutesItsScripts() throws Exception {
        Path marker = temporaryDirectory.resolve("script-was-executed.txt");
        String script = "Set-Content -LiteralPath '" + marker + "' -Value 'unexpected'";
        installLegacySkill(
                "scripted",
                true,
                "# Scripted\n\nRead the instruction only.",
                Map.of("scripts/run.ps1", script));

        List<SkillRunBinding> bindings = catalog.resolveForRun(List.of("scripted"));
        String compiled = catalog.compileInstructions(bindings);

        assertThat(compiled).contains("Read the instruction only");
        assertThat(marker).doesNotExist();
    }

    @Test
    void analyzerResolvesPopularToolAliasesAndInfersScriptRuntimeWithoutExecution() throws Exception {
        String markdown = """
                ---
                name: Coding Helper
                allowed-tools: [Read, Write, Edit, Bash, Grep]
                requirements:
                  runtimes:
                    - name: python
                      version: ">=3.11"
                  network: none
                ---
                # Coding Helper
                Inspect files before editing.
                """;
        installLegacySkill(
                "coding-helper",
                true,
                markdown,
                Map.of("scripts/check.py", "print('static fixture; never execute')"));
        List<SkillRunBinding> bindings = catalog.resolveForRun(List.of("coding-helper"));

        SkillAnalysis analysis = new SkillAnalyzer(catalog).analyze(bindings).getFirst();

        assertThat(analysis.level()).isEqualTo(3);
        assertThat(analysis.requiredTools())
                .containsExactly("fs.read", "fs.write", "fs.apply_patch", "shell.run", "fs.search");
        assertThat(analysis.runtimes()).singleElement().satisfies(runtime -> {
            assertThat(runtime.name()).isEqualTo("python");
            assertThat(runtime.versionConstraint()).isEqualTo(">=3.11");
        });
        assertThat(analysis.requiredFeatures()).contains("skill.script.runtime.v1");
    }

    @Test
    void installsAClawHubArchiveWithSkillAtTheArchiveRoot() throws Exception {
        byte[] archive = rootSkillArchive("""
                ---
                name: Registry Skill
                description: Installed from a verified registry archive.
                ---
                # Registry Skill

                Keep this workflow focused.
                """);
        ClawHubSkillService registry = new ClawHubSkillService(objectMapper) {
            @Override
            public ClawHubInstall download(String reference) {
                return new ClawHubInstall("Registry Skill", "Registry description", reference,
                        "1.2.3", "https://clawhub.ai/test/skills/registry-skill", archive);
            }
        };

        SkillView installed = catalog.installClawHub(
                new InstallClawHubSkillCommand("test/registry-skill", null, true, false), registry);

        assertThat(installed.id()).isEqualTo("registry-skill");
        assertThat(installed.sourceRepository()).isEqualTo("clawhub/test/registry-skill");
        assertThat(catalog.get(installed.id()).skillMarkdown()).contains("Keep this workflow focused.");
    }

    @Test
    void createsDisabledLocalDraftsAndEditsContentWithoutDiscardingResources() throws Exception {
        SkillView created = catalog.create(new CreateSkillCommand(
                "Release Notes", "# Release Notes\n\nDraft a concise release note.", false, false));

        assertThat(created.id()).isEqualTo("release-notes");
        assertThat(created.enabled()).isFalse();
        assertThat(created.sourceRepository()).isEqualTo("local/authoring");
        assertThatThrownBy(() -> catalog.resolveForRun(List.of(created.id())))
                .hasMessageContaining("Skill is disabled");

        installLegacySkill(
                "editable", false, "# Editable\n\nOld instruction.", Map.of("references/style.md", "Use short sentences."));
        SkillView updated = catalog.updateContent(
                "editable", new UpdateSkillContentCommand("# Editable\n\nNew instruction.", true));

        assertThat(updated.enabled()).isTrue();
        assertThat(catalog.get("editable").files()).contains("references/style.md");
        assertThat(catalog.compileInstructions(catalog.resolveForRun(List.of("editable"))))
                .contains("New instruction.")
                .doesNotContain("Old instruction.");
    }

    private byte[] rootSkillArchive(String markdown) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("SKILL.md"));
            zip.write(markdown.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("references/guide.md"));
            zip.write("Reference content".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return output.toByteArray();
    }

    private Path installLegacySkill(
            String id,
            boolean enabled,
            String markdown,
            Map<String, String> additionalFiles) throws Exception {
        Path directory = installDirectory.resolve(id);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("SKILL.md"), markdown, StandardCharsets.UTF_8);
        for (Map.Entry<String, String> entry : additionalFiles.entrySet()) {
            Path file = directory.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue(), StandardCharsets.UTF_8);
        }
        long size;
        try (var files = Files.walk(directory)) {
            size = files
                    .filter(Files::isRegularFile)
                    .mapToLong(path -> {
                        try {
                            return Files.size(path);
                        } catch (Exception ex) {
                            throw new IllegalStateException(ex);
                        }
                    })
                    .sum();
        }
        writeMetadata(id, enabled, null, null, additionalFiles.size() + 1, size);
        return directory;
    }

    private void writeMetadata(
            String id,
            boolean enabled,
            String resolvedCommit,
            String digest,
            int fileCount,
            long sizeBytes) throws Exception {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("id", id);
        metadata.put("name", id);
        metadata.put("description", "Fixture skill " + id);
        metadata.put("enabled", enabled);
        metadata.put("installedAt", Instant.parse("2026-08-01T00:00:00Z"));
        metadata.put("sourceRepository", "fixture/skills");
        metadata.put("sourceUrl", "https://github.com/fixture/skills");
        metadata.put("ref", "main");
        if (resolvedCommit != null) {
            metadata.put("resolvedCommit", resolvedCommit);
        }
        if (digest != null) {
            metadata.put("digest", digest);
        }
        metadata.put("path", id);
        metadata.put("fileCount", fileCount);
        metadata.put("sizeBytes", sizeBytes);
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(installDirectory.resolve(id).resolve(".agent-studio-skill.json").toFile(), metadata);
    }

    private Path releasePath(SkillRunBinding binding) {
        String hex = binding.digest().substring("sha256:".length());
        return installDirectory.getParent().resolve("skill-releases").resolve(binding.skillId()).resolve(hex);
    }
}
