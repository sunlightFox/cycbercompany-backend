package io.github.yourname.agentstudio.mcp;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface McpToolInvocationRepository extends JpaRepository<McpToolInvocationEntity, String> {

    List<McpToolInvocationEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);
}
