package io.github.yourname.agentstudio.mod;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity(name = "mod_installation")
@Table(
        uniqueConstraints = @UniqueConstraint(name = "uk_mod_installation_owner_mod", columnNames = {"tenant_id", "user_id", "mod_id"}),
        indexes = @Index(name = "ix_mod_installation_owner", columnList = "tenant_id,user_id"))
public class ModInstallationEntity {
    @Id
    private String id;
    private String tenantId;
    private String userId;
    private String modId;
    private Instant installedAt;

    protected ModInstallationEntity() {
    }

    public ModInstallationEntity(String id, String tenantId, String userId, String modId, Instant installedAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.userId = userId;
        this.modId = modId;
        this.installedAt = installedAt;
    }
}
