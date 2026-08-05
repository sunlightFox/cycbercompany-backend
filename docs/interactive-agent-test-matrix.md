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
- Treat a node capability as usable only when its current tool entry is enabled;
  an advertised-but-disabled tool must fail preflight before a Run is created.
- For Office attachments, verify a location-bearing value (for example, a sheet
  coordinate or slide number) in both the extracted context and final answer.

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
| SMOKE-01 | Native interaction smoke | Run the reusable isolated smoke harness | Node health, approval resume, process, browser trace, API assertion | All checks pass and the managed process is stopped. |
| RECOVERY-01 | Node reconnect | Restart backend during an active node connection | Reconnect, capabilities, heartbeat | Node returns ONLINE without duplicate side effects. |
| RECOVERY-02 | Approval resume | Pause on a high-risk operation and approve once | Approval and resumed run events | Exactly one tool execution occurs after approval. |
| EVAL-01 | Full-stack fixture | Run each documented coding-evaluation scenario | Evaluation report and quality score | Score meets the configured threshold with no scope violation. |
| OFFICE-01 | Modern Office attachment | Upload DOCX, XLSX, and PPTX to chat | Header/body, cell coordinate, slide/table text | Each file's requested value is returned from the extracted context without inventing content. |
| OFFICE-02 | Legacy Office attachment | Upload DOC, XLS, and PPT to chat | DOC excerpt, XLS coordinate, PPT slide text | Files complete without a hang and preserve location-bearing extracted text. |
| OFFICE-03 | Knowledge-base Office import | Upload a DOC, XLS, or PPT in Knowledge Base management | Import status, chunk count, extracted preview | Import is indexed and a retrieval can cite the uploaded text. |
| OFFICE-04 | Corrupted Office attachment | Upload deliberately invalid XLS and PPT bytes | Bounded run duration and unavailable-content marker | The answer explicitly marks each file unreadable and contains no fabricated document facts. |
| OFFICE-05 | Approval mode continuity | Run a multi-tool Office request with on-request and Full Access modes | Approval events and terminal run state | On-request pauses visibly for approval; Full Access completes the same workflow without approval pauses while retaining audit events. |

## Execution Order

1. Run the focused unit tests for protocol, approvals, tool policy, browser, and
   managed-process behavior.
2. Start a sandbox node against a fresh fixture and run FILE-01, PROCESS-01,
   BROWSER-01, and SMOKE-01. The repeatable smoke command is:

   `.\scripts\run-interactive-agent-smoke.ps1 -NodeId <sandbox-node-id> -WorkingDirectory <fixture-directory>`

   It is restricted to `.tmp-coding-evaluation-fixtures`, automatically approves
   only its own disposable high-risk calls, and writes a redacted JSON report.
3. Run the five coding-evaluation scenarios in isolated fixture directories.
4. Repeat NODE-01 and RECOVERY-01 after backend restart to detect protocol drift.
5. Store only redacted reports and summarize failures by reproducible scenario ID.
