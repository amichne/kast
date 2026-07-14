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
Diagnostics retain semantic completeness, exact full-set severity/cardinality,
and a bounded actionable page with explicit message/preview truncation flags.
Mutation results retain operation and edit-application state, changed files and
edits when available, diagnostic counts, and failure or cancellation evidence.
Verification retains backend, runtime, and capability evidence. Raw
request/response and multi-step envelopes are not part of the default result.
Impact retains its query, bounded source paths, confidence summary, and explicit
total/returned/truncated counts.

Use `--verbose` to preserve the complete validated command envelope. Use
`--explain` when ranking, surrounding-member, indexed fallback, or next-request
evidence is needed; the command requests that extra evidence only for the
detailed view.

JSON consumers can select a family-specific field set with `--fields` or request
aggregates with `--count`:

| Command family | `--fields` values | `--count` retains |
| --- | --- | --- |
| `verify` | `health,runtime,capabilities` | check and capability counts |
| `symbol` | `identity,location,mode,outcome,source,ambiguity,relationships` | result, candidate, and exact-or-known-minimum relationship cardinality |
| `impact` | `query,summary,nodes,confidence` | total, returned, and truncated node counts |
| `diagnostics` | `analysis,diagnostics,severity-counts` | analyzed/skipped, exact severity counts, and cardinality |
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
canonical fully-qualified name. `--limit` bounds detailed reference evidence
and caller traversal output. Compact mode caps requested and emitted records at
four for each requested
relationship kind and reports an `EXACT` or `KNOWN_MINIMUM` cardinality,
`returnedCount`, `truncated`, and a reference `nextPageToken` when more results
exist. Continue references with `--reference-page-token <token>`. Tokens are
opaque, one-use handles for bounded server-held traversal. They bind the
workspace, resolved query and options, INDEX or IDEA evidence source, and source
generation, so readiness or PSI changes cannot reinterpret an offset. Unknown,
replayed, mismatched, evicted, and stale tokens fail with a typed conflict;
accepted pages remain deterministic and non-overlapping. Caller and type
hierarchy resolvers may still enumerate the underlying compiler search before
the hierarchy engine applies its typed cap. The public caller result is bounded,
but it does not claim bounded resolver enumeration; pre-materialization resolver
budgets are tracked separately by issue #339.

## Diagnostics

Compact diagnostics request at most eight records while retaining exact
severity counts and exact cardinality for the full compiler result. Messages
are capped at 256 characters and source previews at 160 characters;
`messageTruncated` and `previewTruncated` state whether the displayed text is
complete. Use `--limit <1..500>` for detailed pages and consume
`nextPageToken` with `--page-token <token>`. The first page captures one exact
server-held snapshot. Continuations are opaque, one-use, and bound to the
ordered files, limit, and Kotlin PSI generation; they reuse that snapshot
without workspace refresh or compiler recomputation. Unknown, replayed,
mismatched, evicted, or stale tokens fail with a typed conflict.

## Impact

`kast agent impact --symbol <fq-name>` queries source-index change impact with a
typed `--depth` and `--limit`. The default compact request fetches at most four
impact nodes while SQLite counts the full set separately. The compact result
reports the exact executed query, bounded nodes, confidence evidence, and whether the full node set was
truncated. Use `--fields query,confidence` for metadata without nodes or
`--count` for cardinality only.

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
    kast agent impact --symbol com.example.OrderService --count --workspace-root "$PWD"
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
