package io.github.yourname.agentstudio.model;

import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

@Entity(name = "model_profile")
public class ModelProfileEntity {

    @Id
    private String id;

    @Enumerated(EnumType.STRING)
    private ProviderType providerType;

    private String baseUrl;
    private String modelName;
    private String credentialRef;

    @ElementCollection(fetch = FetchType.EAGER)
    @Enumerated(EnumType.STRING)
    private Set<ModelCapability> capabilities = EnumSet.noneOf(ModelCapability.class);

    private boolean enabled;
    private Instant createdAt;

    protected ModelProfileEntity() {
    }

    public ModelProfileEntity(
            String id,
            ProviderType providerType,
            String baseUrl,
            String modelName,
            String credentialRef,
            Set<ModelCapability> capabilities,
            boolean enabled,
            Instant createdAt) {
        this.id = id;
        this.providerType = providerType;
        this.baseUrl = baseUrl;
        this.modelName = modelName;
        this.credentialRef = credentialRef;
        this.capabilities = capabilities == null ? EnumSet.noneOf(ModelCapability.class) : EnumSet.copyOf(capabilities);
        this.enabled = enabled;
        this.createdAt = createdAt;
    }

    public String id() { return id; }
    public ProviderType providerType() { return providerType; }
    public String baseUrl() { return baseUrl; }
    public String modelName() { return modelName; }
    public String credentialRef() { return credentialRef; }
    public Set<ModelCapability> capabilities() { return Set.copyOf(capabilities); }
    public boolean enabled() { return enabled; }
    public Instant createdAt() { return createdAt; }
}
