# Interactive Agent Test Matrix

This matrix measures whether a chat-driven agent can operate a computer reliably
while preserving approvals, isolation, evidence, and recovery behavior.

## Test Rules

- Use a unique disposable fixture directory for every destructive test.
- Run destructive sandbox scenarios against the dedicated evaluation backend
  (`docker compose -f docker-compose.yml -f docker-compose.evaluation.yml up -d backend-evaluation`)
  on `http://127.0.0.1:8084`, never the shared interactive development backend.
- Never point automated evaluation at a desktop, home directory, repository root,
  or a directory that was not created by the test harness.
- Record the run ID, node ID, approval count, final status, and bounded evidence.
- A visible chat claim is not proof of completion. Verify the resulting file,
  process, browser state, or API response independently.

## Scenarios

| ID | Scenario | Trigger | Required evidence | Pass condition |
| --- | --- | --- | --- | --- |
| CHAT-01 | Streaming response | Send a short non-tool prompt | First token and terminal event | Tokens arrive before completion and the final answer is persisted. |
| CHAT-02 | Provider reconnect | Interrupt a provider stream before completion | Retry result and run lifecycle | Retry either completes once or reports a bounded, actionable failure. |
| NODE-01 | Direct node invocation | Call a low-risk tool through the management API | Protocol envelope and node result | Request uses protocol 1.1 and the client accepts it. |
| NODE-02 | Run-bound invocation | Ask a run to inspect a fixture | Invocation audit and tool result | The result is correlated to run, tool call, digest, and attempt. |
| FILE-01 | Approved recursive delete | Create a unique fixture directory and marker file | Approval record and absent path | The directory is removed only after approval. |
| FILE-02 | Guarded desktop operation | Request a desktop directory delete | Tool contract and approval state | Unsafe organizer tools reject directories and the agent selects system.fs.delete only after target confirmation. |
| BROWSER-01 | Deterministic UI action | Open a local page, act once, verify response | Trace, snapshot, browser.verify | The final response status and visible state match the expected action. |
| PROCESS-01 | Managed development server | Start a fixture server, wait for HTTP, stop it | Process ID, readiness, exit state | No child process remains after cleanup. |
| RECOVERY-01 | Node reconnect | Restart backend during an active node connection | Reconnect, capabilities, heartbeat | Node returns ONLINE without duplicate side effects. |
| RECOVERY-02 | Approval resume | Pause on a high-risk operation and approve once | Approval and resumed run events | Exactly one tool execution occurs after approval. |
| EVAL-01 | Full-stack fixture | Run each documented coding-evaluation scenario | Evaluation report and quality score | Score meets the configured threshold with no scope violation. |

## Execution Order

1. Run the focused unit tests for protocol, approvals, tool policy, browser, and
   managed-process behavior.
2. Start a sandbox node against a fresh fixture and run FILE-01, PROCESS-01,
   and BROWSER-01.
3. Run the five coding-evaluation scenarios in isolated fixture directories.
4. Repeat NODE-01 and RECOVERY-01 after backend restart to detect protocol drift.
5. Store only redacted reports and summarize failures by reproducible scenario ID.
