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

## Put Every Delivery Inside A Task

Start one exact-root task before semantic work and finish the same task before
claiming completion:

```console
kast-agent-task begin --workspace-root "$PWD"
kast agent
kast-agent-task finish --workspace-root "$PWD"
```

`begin` preserves pre-existing dirt as baseline evidence. `finish` validates
only relevant Kotlin, Java, and Gradle-owned changes, binds diagnostics and
Gradle outcomes to final content hashes, and retains a blocked task for repair
and retry. Use `kast-agent-task abort --workspace-root "$PWD"` only to release
the owned task without claiming completion.

Agent commands default to TOON, including on an interactive terminal. Use
`kast agent` and scoped `--help` to discover the current command surface; do
not copy a generated command inventory into provider guidance.

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

If the waiting process disconnects, rerun the exact command with the same key.
The same runtime joins active execution or returns its cached terminal result.
Do not use a new key for the same intended edit.

If the runtime was replaced before the task observed a terminal result, Kast
records `SEMANTIC_MUTATION_OUTCOME_MISSING` and blocks the task. A terminal
mutation failure also blocks further mutations and `finish`. Recover either
case explicitly with `abort` followed by `begin`; do not replay the edit or use
a direct filesystem fallback.

Use [agent commands](../reference/agent-commands.md) for the high-level command
surface and [plan safe edits](plan-safe-edits.md) for mutation behavior.
