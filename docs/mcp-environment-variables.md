# MCP environment variables

Skills provide instructions to the model. MCP connections provide executable tools. Credentials belong to the MCP connection's process environment, not to a Skill, Agent version, or the persisted MCP configuration.

For every MCP server that needs a credential, define the real value in the backend service environment and store an `env:VARIABLE_NAME` reference in the connection's `env` map. The backend resolves that reference only when it starts the MCP STDIO process. API responses expose only environment variable keys, never their values.

For example, a generic imported MCP definition can use:

```json
{
  "mcpServers": {
    "example-service": {
      "command": "npx",
      "args": ["-y", "@example/mcp-server"],
      "env": {
        "SERVICE_TOKEN": "env:EXAMPLE_SERVICE_TOKEN",
        "LOG_LEVEL": "info"
      }
    }
  }
}
```

The backend host then supplies `EXAMPLE_SERVICE_TOKEN` through its deployment secret store or a systemd `EnvironmentFile` with mode `600`. Restart the backend after changing a process environment variable, then refresh the MCP connection's tools. A missing reference is reported with the connection ID and variable name instead of being passed to the MCP server as an empty credential.

This applies equally to every installed MCP-backed Skill. A Skill checkbox does not install or authenticate an MCP server; install/import the corresponding MCP connection, configure its environment references, refresh its tools, then select it in the run.
