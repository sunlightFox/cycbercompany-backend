package io.github.yourname.agentstudio.persona;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity(name = "user_persona")
@Table(uniqueConstraints = @UniqueConstraint(
        name = "uk_user_persona_name",
        columnNames = {"tenant_id", "user_id", "name"}))
public class UserPersonaEntity {

    @Id
    private String id;
    private String tenantId;
    private String userId;
    private String name;
    private String description;
    @Lob
    private String attributesJson;
    private boolean defaultPersona;
    private Instant createdAt;
    private Instant updatedAt;
    @Version
    private long revision;

    protected UserPersonaEntity() {
    }

    public UserPersonaEntity(
            String id,
            String tenantId,
            String userId,
            String name,
            String description,
            String attributesJson,
            boolean defaultPersona,
            Instant now) {
        this.id = id;
        this.tenantId = tenantId;
        this.userId = userId;
        this.name = name;
        this.description = description;
        this.attributesJson = attributesJson;
        this.defaultPersona = defaultPersona;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void update(String name, String description, String attributesJson, Instant now) {
        this.name = name;
        this.description = description;
        this.attributesJson = attributesJson;
        this.updatedAt = now;
    }

    public void setDefaultPersona(boolean value, Instant now) {
        this.defaultPersona = value;
        this.updatedAt = now;
    }

    public String id() { return id; }
    public String tenantId() { return tenantId; }
    public String userId() { return userId; }
    public String name() { return name; }
    public String description() { return description; }
    public String attributesJson() { return attributesJson; }
    public boolean defaultPersona() { return defaultPersona; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public long revision() { return revision; }
}
