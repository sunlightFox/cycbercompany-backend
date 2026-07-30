package io.github.yourname.agentstudio.mcp;

import java.util.List;
import org.springframework.stereotype.Service;

/**
 * MCP boundary placeholder.
 *
 * <p>MCP is deliberately isolated from tool and knowledge internals. The
 * service is small today, but it gives future client/server adapters one entry
 * point without letting protocol DTOs leak into the core modules.
 */
@Service
public class McpConnectionService {
    public List<String> listConnections() {
        return List.of();
    }
}
