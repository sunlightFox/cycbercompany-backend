package io.github.yourname.agentstudio.node;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NodeToolInvocationRepository extends JpaRepository<NodeToolInvocationEntity, String> {

    List<NodeToolInvocationEntity> findByTenantIdAndRunIdOrderByCreatedAtAsc(String tenantId, String runId);
}
