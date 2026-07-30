package io.github.yourname.agentstudio.tool;

import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Lists tools that the agent runtime may expose.
 *
 * <p>Execution policy is intentionally separate from discovery. A tool being
 * visible in the catalog does not mean every agent or tenant can call it.
 */
@Service
public class ToolCatalog {

    public List<RegisteredTool> list() {
        return List.of(
                new RegisteredTool("local_time", "Return the server's current time.", RiskLevel.LOW, false),
                new RegisteredTool("knowledge_search", "Search tenant-scoped knowledge bases.", RiskLevel.LOW, false));
    }
}
