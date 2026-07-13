---
title: Automate With Agents
description: Use Kast as the hidden semantic layer behind agent workflows.
icon: lucide/bot
---

# Automate With Agents

Use this guide when you are authoring or reviewing an agent workflow. For most
developers, Kast should be out of sight: the developer installs it, opens the
project, and the agent uses typed semantic operations when a task needs
compiler-backed evidence.

## Keep The Public Dialect Typed

Agents should ask for named semantic operations: readiness, symbol identity,
diagnostics, impact, rename planning, and scoped Kotlin mutations. They should
not depend on raw transport, generated catalog lookup, byte offsets, or
implementation class names.

Keep automation on the public command dialect so agent behavior stays
reviewable.

## Prefer Readable Or JSON Output

Human-facing output should be readable. Agent and CI workflows that need a
stable parser contract should request JSON explicitly. Public documentation
should not require readers to understand internal compact output choices.

## Let Setup Stay Invisible

On macOS, the IntelliJ plugin prepares the project when it opens. On non-macOS
headless hosts, repository guidance is normally part of image bootstrap or the
agent setup flow.

??? info "Agent bootstrap commands"
    These commands are for agent authors and hosted environments, not normal
    developer setup.

    ```console
    kast setup --dry-run --workspace-root "$PWD"
    kast setup --workspace-root "$PWD"
    kast agent verify --workspace-root "$PWD"
    ```

    Setup installs only the packaged Kast skill and one managed guidance region
    in the selected context file.

## Use Evidence Before Edits

An agent should resolve identity and check backend state before it asks Kast to
plan an edit. It should apply an edit only after reviewing the planned target,
diagnostics, conflicts, and write set.

For each applied plan, choose a stable idempotency key and retain it with the
task state:

```console
kast agent add-file \
  --file-path "$PWD/src/main/kotlin/com/example/Added.kt" \
  --content-file /tmp/Added.kt \
  --apply \
  --idempotency-key add-example-file \
  --workspace-root "$PWD"
```

## Recover An Interrupted Mutation

If the submitting process yields or disconnects, query with the same key. Do
not submit a new key for the same intended edit.

```console
kast agent operation status \
  --idempotency-key add-example-file \
  --workspace-root "$PWD"
```

If the operation should stop, request cancellation and poll status until the
state is terminal:

```console
kast agent operation cancel \
  --idempotency-key add-example-file \
  --workspace-root "$PWD"
kast agent operation status \
  --idempotency-key add-example-file \
  --workspace-root "$PWD"
```

Use a direct filesystem fallback only when a successfully retrieved terminal
state reports `editApplicationState: NOT_STARTED`. `STARTED` and `COMPLETED`
both mean the filesystem may already have changed. If the backend daemon
restarted and no retained state can be retrieved, the outcome is ambiguous;
inspect and reconcile the workspace instead of applying a fallback or retrying
under a new key.

Use [agent commands](../reference/agent-commands.md) for the high-level command
surface and [plan safe edits](plan-safe-edits.md) for mutation behavior.
