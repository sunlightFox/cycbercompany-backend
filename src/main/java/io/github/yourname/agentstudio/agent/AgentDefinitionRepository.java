package io.github.yourname.agentstudio.agent;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentDefinitionRepository extends JpaRepository<AgentDefinitionEntity, String> {
}
