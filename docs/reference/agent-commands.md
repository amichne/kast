---
title: Agent Commands
description: High-level reference for the typed Kast agent command surface.
icon: lucide/bot
---

# Agent Commands

`kast agent` is the typed surface agents use when they need compiler-backed
Kotlin evidence. Most developers should not need to run these commands by hand;
they exist so agent workflows stay predictable instead of falling back to raw
transport, generated catalog lookup, byte offsets, or implementation class
names.

## What Agents Ask For

| Agent need | Kast capability | Why it matters |
| --- | --- | --- |
| Confirm semantic readiness | Backend verification | Avoids acting on stale IDE or headless state |
| Find the declaration behind a name | Symbol identity | Distinguishes real Kotlin declarations from matching text |
| Understand usage | References, callers, and impact | Gives bounded semantic evidence before changing code |
| Check a touched file | Diagnostics | Confirms the backend sees the same source state |
| Rename safely | Identity-first rename planning | Surfaces target identity, conflicts, and write set before mutation |
| Add or replace Kotlin | Plan-first mutations | Places content using a typed file, scope, or declaration target |
| Recover an interrupted edit | Mutation operation status | Retrieves retained progress and terminal results after disconnects |
| Stop an in-flight edit | Typed operation cancellation | Requests cooperative cancellation without inventing a rollback |
| Serve editor integrations | LSP bridge | Lets editors reuse the same backend |

## Output For Humans And Automation

Interactive use should stay readable. Automation that needs a parser contract
should request JSON explicitly. The public docs only describe those two output
shapes.

## Mutation Boundary

Agent edits are plan-first. Kast reports the selected target, planned write set,
diagnostics, and conflicts before any write. The agent applies the operation
only after the plan matches the requested change. Every applied mutation
requires `--idempotency-key <stable-key>` and returns one stable operation ID.
Repeating the same key and request retrieves the same operation; binding the key
to another request fails before mutation.

Operation state is retained for the lifetime of the backend daemon. Retention
does not survive a daemon restart.

Use [mutation selectors](mutation-selectors.md) for the selector model and
[plan safe edits](../use/plan-safe-edits.md) for the developer-facing story.

??? info "Command names for agent authors"
    The current typed agent commands are:

    - `kast agent verify`
    - `kast agent symbol`
    - `kast agent impact`
    - `kast agent diagnostics`
    - `kast agent rename`
    - `kast agent add-file`
    - `kast agent add-declaration`
    - `kast agent add-implementation`
    - `kast agent add-statement`
    - `kast agent replace-declaration`
    - `kast agent operation status`
    - `kast agent operation cancel`
    - `kast agent lsp`

??? info "Example agent execution"
    These examples are for agent authors and support workflows, not the normal
    developer install path.

    ```console
    kast agent verify --workspace-root "$PWD"
    kast agent symbol --query OrderService --workspace-root "$PWD"
    kast agent diagnostics \
      --file-path "$PWD/src/main/kotlin/App.kt" \
      --workspace-root "$PWD"
    kast agent rename \
      --symbol com.example.OrderService \
      --new-name Orders \
      --workspace-root "$PWD"
    kast agent rename \
      --symbol com.example.OrderService \
      --new-name Orders \
      --apply \
      --idempotency-key rename-order-service \
      --workspace-root "$PWD"
    kast agent operation status \
      --idempotency-key rename-order-service \
      --workspace-root "$PWD"
    ```
