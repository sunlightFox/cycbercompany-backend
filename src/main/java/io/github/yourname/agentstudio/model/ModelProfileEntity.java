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

/**
 * 一个可调用的模型配置。
 *
 * <p>Profile 把 Provider、endpoint、模型名、密钥引用和能力标签放在一起。它不是模型本身，
 * 而是“如何找到并调用某个模型”的运行配置。
 */
@Entity(name = "model_profile")
public class ModelProfileEntity {

    @Id
    /** Profile 的稳定 ID，RunSpec 会保存这个 ID。 */
    private String id;

    @Enumerated(EnumType.STRING)
    /** Provider 类型，例如 OpenAI-compatible。 */
    private ProviderType providerType;

    /** 模型服务的基础 URL，不包含密钥。 */
    private String baseUrl;
    /** 具体模型名，例如 MiniMax-M3 或 gpt-4o-mini。 */
    private String modelName;
    /** 环境变量名称，推荐通过它读取密钥。 */
    private String credentialRef;
    /** 可选的本地配置密钥；API View 不会直接返回它。 */
    private String apiKey;

    @ElementCollection(fetch = FetchType.EAGER)
    @Enumerated(EnumType.STRING)
    /** 模型能力集合，用于在调用前检查图片、工具、Embedding 等需求。 */
    private Set<ModelCapability> capabilities = EnumSet.noneOf(ModelCapability.class);

    /** 禁用后不能被选择为默认模型或新 Run 模型。 */
    private boolean enabled;
    /** Profile 创建时间。 */
    private Instant createdAt;

    protected ModelProfileEntity() {
    }

    public ModelProfileEntity(
            String id,
            ProviderType providerType,
            String baseUrl,
            String modelName,
            String credentialRef,
            String apiKey,
            Set<ModelCapability> capabilities,
            boolean enabled,
            Instant createdAt) {
        this.id = id;
        this.providerType = providerType;
        this.baseUrl = baseUrl;
        this.modelName = modelName;
        this.credentialRef = credentialRef;
        this.apiKey = apiKey;
        this.capabilities = capabilities == null ? EnumSet.noneOf(ModelCapability.class) : EnumSet.copyOf(capabilities);
        this.enabled = enabled;
        this.createdAt = createdAt;
    }

    public String id() { return id; }
    public ProviderType providerType() { return providerType; }
    public String baseUrl() { return baseUrl; }
    public String modelName() { return modelName; }
    public String credentialRef() { return credentialRef; }
    public String apiKey() { return apiKey; }
    public Set<ModelCapability> capabilities() { return Set.copyOf(capabilities); }
    public boolean enabled() { return enabled; }
    public Instant createdAt() { return createdAt; }

    public void update(
            ProviderType providerType,
            String baseUrl,
            String modelName,
            String credentialRef,
            String apiKey,
            Set<ModelCapability> capabilities,
            boolean enabled) {
        this.providerType = providerType;
        this.baseUrl = baseUrl;
        this.modelName = modelName;
        this.credentialRef = credentialRef;
        if (apiKey != null && !apiKey.isBlank()) {
            this.apiKey = apiKey;
        }
        this.capabilities = capabilities == null ? EnumSet.noneOf(ModelCapability.class) : EnumSet.copyOf(capabilities);
        this.enabled = enabled;
    }

    /** Adds capabilities from a newer built-in default without replacing user-managed profile fields. */
    public boolean addMissingCapabilities(Set<ModelCapability> requiredCapabilities) {
        if (requiredCapabilities == null || requiredCapabilities.isEmpty()) {
            return false;
        }
        return capabilities.addAll(requiredCapabilities);
    }
}
