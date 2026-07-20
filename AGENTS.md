# Repository Guidelines

## Task contract protocol

For every implementation task:

1. Before modifying source code, create or replace `.agent/TASK.md` using the required template below.
2. Populate it solely from the user's request. Do not invent additional goals.
3. Read `.agent/TASK.md` immediately before:
   - making the first source change;
   - starting any investigation not explicitly listed;
   - running verification;
   - declaring completion.
4. Treat `.agent/TASK.md` as the authoritative and closed execution scope.
5. Do not modify the Goal, Allowed Writes, Non-Goals, Red Proof, Green Proof, or Done When sections after implementation begins.
6. Record progress only under Execution State.
7. Any work not required by the Goal or Done When criteria is prohibited.
8. If an action would exceed scope, do not perform it. Record it under Out-of-Scope Findings only when it blocks completion.
9. Stop immediately when every Done When condition is satisfied.
10. Do not perform cleanup, hardening, refactoring, documentation, or additional testing after completion unless explicitly required by the contract.

If `.agent/TASK.md` cannot be completed from the request, make the narrowest reasonable assumption. Ask a question only when no implementation can proceed safely.

### Required `.agent/TASK.md` template

````
# Task Contract

## Goal

One observable outcome.

## Allowed Writes

- Exact file or directory paths.

No other paths may be modified.

## Allowed Reads

- Relevant file or directory paths.

## Non-Goals

- Explicitly excluded adjacent work.
- Refactoring unrelated code.
- Generalizing the implementation.
- Fixing unrelated failures.
- Adding optional improvements.

## Red Proof

Command:

```shell
<command>
```

Expected failure:

<specific failure proving the missing behavior>

## Green Proof

Command:

```shell
<command>
```

## Done When

- The requested observable behavior exists.
- The Green Proof passes.
- No files outside Allowed Writes changed.
- No Non-Goal work was performed.


## Out-of-Scope Findings

- None
````

<kast>
## Kast routing
Start Kotlin or Gradle work with `kast-agent-task begin --workspace-root "$PWD"`.
Discover the current task and available semantic operations with `kast agent` and scoped `--help`.
Finish with `kast-agent-task finish --workspace-root "$PWD"`.
Report typed blockers exactly as returned; do not bypass task ownership, diagnostics, or Gradle proof.
</kast>
