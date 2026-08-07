package io.github.yourname.agentstudio.agent;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity(name = "agent_version")
@Table(uniqueConstraints = @UniqueConstraint(
        name = "uk_agent_version_number",
        columnNames = {"tenant_id", "agent_id", "version_number"}))
public class AgentVersionEntity {

    @Id
    private String id;
    private String agentId;
    private String tenantId;
    private int versionNumber;
    private int schemaVersion;
    private String state;
    @Lob
    private String manifestJson;
    private String manifestDigest;
    @Lob
    private String compiledSystemPrompt;
    private String compiledPromptDigest;
    @Lob
    private String toolAllowList;
    private String defaultModelProfileId;
    private String createdBy;
    private Instant createdAt;
    private Instant publishedAt;
    @Version
    private long revision;

    protected AgentVersionEntity() {
    }

    public AgentVersionEntity(
            String id,
            String agentId,
            String tenantId,
            int versionNumber,
            AgentManifestCompiler.CompiledManifest manifest,
            String createdBy,
            Instant createdAt) {
        this.id = id;
        this.agentId = agentId;
        this.tenantId = tenantId;
        this.versionNumber = versionNumber;
        this.schemaVersion = manifest.schemaVersion();
        this.state = AgentVersionState.DRAFT.name();
        replaceManifest(manifest);
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public void replaceManifest(AgentManifestCompiler.CompiledManifest manifest) {
        if (!AgentVersionState.DRAFT.name().equals(state)) {
            throw new IllegalStateException("Only a draft Agent version can be edited.");
        }
        schemaVersion = manifest.schemaVersion();
        manifestJson = manifest.canonicalJson();
        manifestDigest = manifest.manifestDigest();
        compiledSystemPrompt = manifest.systemPrompt();
        compiledPromptDigest = manifest.promptDigest();
        toolAllowList = manifest.toolAllowList();
        defaultModelProfileId = manifest.defaultModelProfileId();
    }

    public void publish(Instant now) {
        if (!AgentVersionState.DRAFT.name().equals(state)) {
            throw new IllegalStateException("Only a draft Agent version can be published.");
        }
        state = AgentVersionState.PUBLISHED.name();
        publishedAt = now;
    }

    public void preserveLegacyRuntimeSnapshot(
            String systemPrompt,
            String defaultModelProfileId,
            String toolAllowList) {
        if (!AgentVersionState.DRAFT.name().equals(state)) {
            throw new IllegalStateException("Only a draft Agent version can preserve a legacy runtime snapshot.");
        }
        this.compiledSystemPrompt = systemPrompt;
        this.compiledPromptDigest = AgentManifestCompiler.digest(systemPrompt);
        this.defaultModelProfileId = defaultModelProfileId;
        this.toolAllowList = toolAllowList == null ? "" : toolAllowList;
    }

    public String id() { return id; }
    public String agentId() { return agentId; }
    public String tenantId() { return tenantId; }
    public int versionNumber() { return versionNumber; }
    public int schemaVersion() { return schemaVersion; }
    public AgentVersionState state() { return AgentVersionState.valueOf(state); }
    public String manifestJson() { return manifestJson; }
    public String manifestDigest() { return manifestDigest; }
    public String compiledSystemPrompt() { return compiledSystemPrompt; }
    public String compiledPromptDigest() { return compiledPromptDigest; }
    public String toolAllowList() { return toolAllowList; }
    public String defaultModelProfileId() { return defaultModelProfileId; }
    public String createdBy() { return createdBy; }
    public Instant createdAt() { return createdAt; }
    public Instant publishedAt() { return publishedAt; }
    public long revision() { return revision; }
}
