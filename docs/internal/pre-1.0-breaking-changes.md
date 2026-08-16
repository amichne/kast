# Pre-1.0 breaking change log

This log is temporary. Delete it only after version 1.0 is released and verified.

Add one entry for each breaking change or removal:

```markdown
## YYYY-MM-DD — Short change name

- Introduced by: issue, PR, and commit
- Removed: prior behavior or contract
- Replacement: current behavior or `none`
- Reason: initial-release requirement served by the change
- Paths: affected source and contract owners
- Proof: tests or verification that prove the replacement
```

## 2026-08-06 — Session-local planning

- Introduced by: current agent-policy change; record the PR and commit before merge
- Removed: repository task contracts in `.agents/TASK.md`
- Replacement: Codex session-local plans and `.agents/RED-GREEN.md` executable evidence
- Reason: keep planning local while retaining durable TDD proof
- Paths: `AGENTS.md`
- Proof: repository policy contains no repository task-plan workflow
