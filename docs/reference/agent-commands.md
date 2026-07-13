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

## Verification Evidence

`kast agent verify` reports the semantic workspace that supplied its evidence.
The workspace identity is the exact normalized root passed with
`--workspace-root`; a runtime registered for another clone or Git worktree is
never eligible, even when both checkouts share a branch or commit.

| Field | Meaning |
| --- | --- |
| Backend name | The selected `idea` or `headless` runtime |
| Workspace root | The exact checkout whose semantic state was queried |
| Workspace kind | Primary checkout, linked worktree, disposable checkout, or standalone Gradle workspace |
| Source module names | The Gradle source modules reported by the runtime |
| Limitations | Indexing, missing reference-index, unavailable source-module, or preparation constraints on the evidence |
| Evidence quality | `COMPILER_BACKED` after a matching runtime response, or `UNAVAILABLE` when no semantic evidence was admitted |
| Next actions | Non-mutating recovery choices when the requested root is unprepared |

An unprepared supported Gradle workspace returns
`SEMANTIC_WORKSPACE_UNPREPARED`. An unsupported non-Gradle directory returns
`SEMANTIC_WORKSPACE_UNSUPPORTED`. Neither outcome borrows another checkout's
state or prepares the directory on the caller's behalf. Verification is
reuse-only: it does not launch IDEA, start a headless runtime, prune dead
descriptors, or rewrite the descriptor registry.

Automatic selection returns `SEMANTIC_BACKEND_AMBIGUOUS` when more than one
backend kind is ready for the exact root. The error includes each candidate's
backend name, version, root, readiness, and evidence quality. Select
`--backend=idea` or `--backend=headless` explicitly; Kast does not prefer one
candidate by operating system or backend name. When exactly one backend kind
is ready, automatic selection uses it even when it differs from the host
fallback.

## Workspace-relative file paths

When a command supplies explicit `--workspace-root`, Kotlin target arguments
such as diagnostics `--file-path`, add-file `--file-path`, and mutation
`--inside-file` may be relative to that root. Kast resolves each target once,
rejects workspace or symlink escapes, and reports the canonical absolute path
sent to the backend. Absolute in-workspace targets remain supported.

## Compact result views

Agent results are compact by default. Symbol results retain identity, location,
lookup mode, ambiguity, and only the relationships the command requested.
Diagnostics retain semantic completeness counts and actionable diagnostics.
Mutation results retain operation and edit-application state, changed files and
edits when available, and diagnostic counts. Verification retains backend,
runtime, and capability evidence. Raw request/response and multi-step envelopes
are not part of the default result.

Use `--verbose` to preserve the complete validated command envelope. Use
`--explain` when ranking, surrounding-member, or next-request evidence is needed;
the command requests that extra evidence only for the detailed view.

JSON consumers can select a family-specific field set with `--fields` or request
aggregates with `--count`:

| Command family | `--fields` values | `--count` retains |
| --- | --- | --- |
| `verify` | `health,runtime,capabilities` | check and capability counts |
| `symbol` | `identity,location,mode,outcome,source,ambiguity,relationships` | result, candidate, and relationship counts |
| `diagnostics` | `analysis,diagnostics,severity-counts` | analyzed/skipped and severity counts |
| mutations and `operation` | `operation,state,edits,files,diagnostics` | lifecycle state and edit/file/diagnostic counts |

Unknown or cross-family fields fail during argument parsing. `--fields` and
`--count` cannot be combined with each other or with a detailed view.

```console
kast --output json agent symbol \
  --query OrderService \
  --fields identity,location \
  --workspace-root "$PWD"
kast --output json agent diagnostics \
  --file-path src/main/kotlin/App.kt \
  --count \
  --workspace-root "$PWD"
```

## Mutation Boundary

Agent edits are plan-first. Kast reports the selected target, planned write set,
diagnostics, and conflicts before any write. The agent applies the operation
only after the plan matches the requested change. Every applied mutation
requires `--idempotency-key <stable-key>` and returns one stable operation ID.
Repeating the same key and request retrieves the same operation; binding the key
to another request fails before mutation.

Operation state is retained for the lifetime of the backend daemon. Retention
does not survive a daemon restart.

On macOS, every applied public mutation requires valid plugin preparation for
the exact root. The read-only unprepared/headless route cannot authorize
`rename --apply`, add-file, add-declaration, add-implementation, add-statement,
or replace-declaration; those commands return
`SEMANTIC_MUTATION_AUTHORITY_REQUIRED` before descriptor discovery or backend
contact. Automatic and explicit routes use the same authority preflight. An
explicit headless backend is allowed only after the same prepared authority
exists.

Use [mutation selectors](mutation-selectors.md) for the selector model and
[plan safe edits](../use/plan-safe-edits.md) for the developer-facing story.

## Symbol Lookup

`kast agent symbol --query <name>` defaults to `--mode exact`. Exact mode accepts
a simple or fully-qualified Kotlin name and applies `--kind`, `--file-hint`, and
`--containing-type` as hard constraints. Backticks affect matching only; a
resolved result reports the canonical identity returned by the compiler or
source index.

The lookup outcome is one of `RESOLVED`, `NOT_FOUND`, or `AMBIGUOUS`. Its
`source` is `compiler` for compiler-backed identity or `indexed-exact` when the
compiler is unavailable and the source index can prove the exact constraints.
Not-found and ambiguous outcomes never trigger fuzzy search.

`--mode discovery` is the explicit fuzzy surface. It reports `DISCOVERED` with
`source: fuzzy`; `--references` and `--callers` are unavailable in that mode.
Relation requests run only after compiler resolution and use the returned
canonical fully-qualified name.

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
    kast agent symbol --query order --mode discovery --workspace-root "$PWD"
    kast agent symbol --query OrderService --explain --workspace-root "$PWD"
    kast agent diagnostics \
      --file-path src/main/kotlin/App.kt \
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
