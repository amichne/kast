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
| Bind one worker to one semantic runtime | Exact-root workspace lease | Prevents cross-worktree, backend, runtime, generation, and session drift |
| Confirm semantic readiness | Backend verification | Avoids acting on stale IDE or headless state |
| Discover owned Kotlin files | Workspace file discovery | Composes compiler/project-model and source-index evidence without recursive search |
| Find the declaration behind a name | Symbol identity | Distinguishes real Kotlin declarations from matching text |
| Understand usage | References, callers, and impact | Gives bounded semantic evidence before changing code |
| Check a touched file | Diagnostics | Confirms the backend sees the same source state |
| Rename safely | Identity-first rename planning | Surfaces target identity, conflicts, and write set before mutation |
| Add or replace Kotlin | Plan-first mutations | Places content using a typed file, scope, or declaration target |
| Recover an interrupted edit | Same-key retry | Joins or retrieves the terminal result from the same runtime |
| Serve editor integrations | LSP bridge | Lets editors reuse the same backend |


## Exact-Root Workspace Leases

Acquire a lease before a coordinated semantic-work session:

```console
kast --output toon agent lease acquire --workspace-root "$PWD"
```

The `READY` result contains an opaque `leaseId`, canonical `workspaceRoot`,
workspace classification, selected backend, full runtime and process-start
identity, effective install authority and generation, owner identity, and
`BORROWED` ownership. Acquisition accepts only a fully `READY` IDEA runtime
and never launches or terminates the IDE.

Pass `--workspace-root`, `--backend idea`, and `--lease-id` to every semantic or
operation command in the session. Kast authenticates the token and record,
then rechecks the same root, backend, live owner/session, effective generation,
and exact ready runtime before opening a semantic session.

```console
kast agent lease status --workspace-root "$PWD" --lease-id <id>
kast agent verify --workspace-root "$PWD" --backend idea --lease-id <id>
kast agent lease release --workspace-root "$PWD" --lease-id <id>
```

Status reports `READY`, `ABANDONED`, `FAILED`, or terminal `RELEASED`. One live
lease owns an exact root/IDEA instance; another acquisition returns
`WORKSPACE_LEASE_CONFLICT`. A later acquisition may recover an abandoned owner
using process-start evidence. Release is idempotent and leaves IDEA running.
Wrong-root, wrong-backend, foreign-session/authority,
tampered-record/token, stale-generation, replaced-runtime, and unavailable
runtime failures remain distinct typed outcomes.

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

## Workspace File Discovery

`kast agent workspace-files` returns Kotlin source and script paths owned by
the exact admitted semantic workspace. Its candidate authorities are the
compiler/project model and the exact-root `.kt` source index. Targeted
filesystem and Git reads annotate those candidates; neither recursive
filesystem discovery nor Git listing adds candidates.

The command first exhausts the internal generation-bound backend pages and
reads one generation/progress/pending-aware source-index snapshot. It accepts
the composition only after the kind-relevant backend, index, targeted
filesystem, and requested Git lanes reproduce the same discriminated
`AVAILABLE(stamp)` or `UNAVAILABLE(reason)` state. A relevant change retries
the composition once. A second change returns typed partial evidence. Raw
snapshot and module-page handles remain server-held implementation details and
are not a public command surface.

### Arguments

| Argument | Contract |
| --- | --- |
| `--workspace-root <path>` | Uses this exact normalized workspace root; when omitted, the current directory supplies the root. Another checkout or worktree is never substituted |
| `--backend idea|headless` | Selects one admitted runtime when automatic selection is ambiguous |
| `--lease-id <opaque-id>` | Revalidates an acquired exact-root lease before any semantic session opens; requires explicit root and backend |
| `--module backend:<name>` | Matches an exact backend module owner |
| `--module gradle:<build-root>#<project-path>` | Matches a model-proven indexed Gradle owner; the build root is workspace-relative and the project path is absolute, such as `gradle:included/tools#:app` |
| `--source-set <name>` | Matches an exact model-proven, build-qualified Gradle source-set name |
| `--kind source|script` | Selects `.kt` sources or `.kts` scripts; omitting it selects both |
| `--package root` | Matches only `PROVEN_ROOT` package evidence |
| `--package named:<fq-name>` | Matches one exact compiler/PSI-proven canonical Kotlin package |
| `--dirty clean|dirty|unknown` | Matches the typed Git state |
| `--drift none|filesystem-only|index-only|missing-on-disk|not-applicable|unknown` | Matches the cross-source classification |
| `--path-prefix <path>` | Matches one normalized workspace-relative prefix; absolute and parent-traversing values fail |
| `--glob <glob>` | Matches one bounded workspace-relative glob; regex-prefixed patterns fail |
| `--limit <1..200>` | Bounds the page; defaults to 20 |
| `--page-token <token>` | Consumes the opaque, one-use token from the identical normalized query; conflicts with `--count` |

All filters are conjunctive and run before the public limit. Results sort by
normalized workspace-relative path and ownership sets sort by typed identity.

### Compact result

The compact result contains the exact `workspaceRoot`, bounded `files`, a
discriminated `EXACT` or `KNOWN_MINIMUM` `cardinality`, `returnedCount`,
`truncated`, optional `nextPageToken`, separate candidate-inventory and
filter-evidence `coverage`, typed `limitations`, and a schema version. Compact
output is budgeted below 120 lines and 1,500 estimated tokens for the standard
high-cardinality fixture.

`files` groups only consecutive globally path-sorted records with identical
typed evidence. Each group contains:

- every sorted backend owner and build-qualified indexed Gradle owner;
- `KOTLIN_SOURCE` or `KOTLIN_SCRIPT`;
- discriminated model-proven or unproven source-set evidence;
- `PROVEN_NAMED`, `PROVEN_ROOT`, `UNPROVEN`, `UNAVAILABLE`, or
  `INVALID_REFERENCE` package evidence;
- `INDEXED`, `NOT_INDEXED`, `NOT_APPLICABLE`, or `UNKNOWN` source-index state;
- `NONE`, `FILESYSTEM_ONLY`, `INDEX_ONLY`, `MISSING_ON_DISK`,
  `NOT_APPLICABLE`, or `UNKNOWN` drift;
- typed clean, dirty, unknown, or not-applicable Git state; and
- a `paths` array with the absolute `filePath` and workspace-relative
  `relativePath` for each file sharing that evidence.

Flattening `files[group].paths` reproduces the globally sorted page. Equal
evidence separated by a different record remains in separate groups, so
grouping never changes path order. `returnedCount` counts path rows, not groups.

Candidate coverage is complete only when every authority relevant to the
selected kind could add no path. Filter coverage is complete only when every
requested predicate is known for every candidate. `EXACT` requires both;
otherwise `KNOWN_MINIMUM` counts only proved matches. `truncated` is true when
an exact total exceeds the page or unseen matches remain possible. A public
token appears only when another currently known matching record exists.
Stable partial compositions may page known records without claiming exactness.
Unstable evidence suppresses continuation.

`.kts` candidates come from the compiler/project model and are
`NOT_APPLICABLE` to the Kotlin source index. Therefore unrelated `.kt` index
progress cannot reduce script-only exactness. Mixed output tracks source and
script coverage separately before computing the overall result. Gradle
declaration semantics remain a separate Gradle DSL index surface.

### Result views

`--fields` accepts `path,module,source-set,kind,package,index,drift,dirty,evidence`
and returns flat per-file records with the common result metadata. `--count`
removes file payloads and returns typed overall and grouped cardinalities.
`--verbose` preserves flat per-file complete validated workspace-file evidence.
`--explain` adds the normalized query and classification/coverage evidence.
These four view choices are mutually exclusive.

### Public continuation failures

`nextPageToken` is bound to the exact root, backend, normalized filters,
selected view or fields, limit, composition digest, last emitted relative
path, and cumulative returned evidence. Reproduce the identical normalized
query to consume it.

`INVALID_WORKSPACE_FILES_PAGE_TOKEN` is a non-retryable status-400 failure for
a malformed, forged, unknown, replayed, or query-mismatched handle.
`STALE_WORKSPACE_FILES_PAGE` is a retryable status-409 failure when any bound
lane changes stamp, availability, or unavailable reason. Neither failure
silently restarts at page one; issue a new unpaged query.

### Drift and source-index states

| File evidence | Public drift | Source-index state |
| --- | --- | --- |
| `.kt` in backend and index, present on disk | `NONE` | `INDEXED` |
| `.kt` in backend only, present on disk | `FILESYSTEM_ONLY` | `NOT_INDEXED` |
| `.kt` in index only, present on disk, with complete possible-owner backend coverage | `INDEX_ONLY` | `INDEXED` |
| `.kt` in index only with incomplete possible-owner coverage | `UNKNOWN` | `INDEXED` |
| Candidate missing on disk | `MISSING_ON_DISK` | Independently proved state |
| `.kt` with unavailable index evidence | `UNKNOWN` | `UNKNOWN` |
| `.kts` present through complete backend evidence | `NOT_APPLICABLE` | `NOT_APPLICABLE` |

Distinct limitations preserve backend absence, runtime indexing, unavailable
project models, unassociated linked roots, repeated inventory staleness,
source-index incompatibility or unavailability, incomplete progress, pending
updates, Git unavailability, composition instability, unprovable containment,
invalid package references, and excluded out-of-root paths. When no relevant
candidate authority is usable, the command fails with
`WORKSPACE_FILE_DISCOVERY_UNAVAILABLE` instead of returning a false empty
success.

Use `files[group].paths[i].filePath` from the compact default, or select a flat
record with `--fields path`. That `filePath` is the direct input for
`kast agent diagnostics --file-path <path>` and for
`kast agent symbol --query <name> --file-hint <path>`.

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
Mutation results retain the terminal result, deduplication evidence, changed
files and edits when available, diagnostic counts, and typed failure evidence.
Verification retains backend, runtime, and capability evidence. Raw
request/response and multi-step envelopes are not part of the default result.
Impact retains its query, bounded source paths, confidence summary, and explicit
total/returned/truncated counts.

Use `--verbose` to preserve the complete validated command envelope. Use
`--explain` when ranking, surrounding-member, indexed fallback, or next-request
evidence is needed; the command requests that extra evidence only for the
detailed view.

TOON consumers can select a family-specific field set with `--fields` or request
aggregates with `--count`:

| Command family | `--fields` values | `--count` retains |
| --- | --- | --- |
| `verify` | `health,runtime,capabilities` | check and capability counts |
| `workspace-files` | `path,module,source-set,kind,package,index,drift,dirty,evidence` | overall and grouped typed cardinalities without file payloads |
| `symbol` | `identity,location,mode,outcome,source,ambiguity,relationships` | result, candidate, and exact-or-known-minimum relationship cardinality |
| `impact` | `query,summary,nodes,confidence` | total, returned, and truncated node counts |
| `diagnostics` | `analysis,diagnostics,severity-counts` | analyzed/skipped, exact severity counts, and cardinality |
| mutations | `result,edits,files,diagnostics` | terminal result and edit/file/diagnostic counts |

Unknown or cross-family fields fail during argument parsing. `--fields` and
`--count` cannot be combined with each other or with a detailed view.

```console
kast --output toon agent symbol \
  --query OrderService \
  --fields identity,location \
  --workspace-root "$PWD"
kast --output toon agent diagnostics \
  --file-path src/main/kotlin/App.kt \
  --count \
  --workspace-root "$PWD"
```

## Mutation Boundary

Agent edits are plan-first. Kast reports the selected target, planned write set,
diagnostics, and conflicts before any write. The agent applies the operation
only after the plan matches the requested change. Every applied mutation
requires `--idempotency-key <stable-key>` and waits for one terminal result.
Repeating the same key and request against the same runtime joins active work or
retrieves the cached terminal result; binding the key to another request fails
before mutation. If the runtime changes before a terminal response is observed,
Kast blocks the task instead of replaying the edit.

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
`source: fuzzy`. Relationship navigation is a separate exact surface:
`references`, `callers`, `callees`, `implementations`, and `hierarchy` each
require the resolved `fqName`, `declarationFile`, and
`declarationStartOffset`; optional `kind` and `containingType` assertions remain
part of that selector. Compact pages default to four records and report an
`EXACT` or `KNOWN_MINIMUM` cardinality, `returnedCount`, `truncated`, and
`nextPageToken`. Repeat the same selector, direction/depth, and limit with
`--page-token <token>` to continue. Tokens are opaque and query-bound; replay,
mismatch, eviction, and semantic-generation movement return distinct typed
outcomes instead of restarting at page one.

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

`kast agent impact` accepts the same exact selector as relationship navigation,
plus `--depth <1..8>`, `--limit <1..200>`, and `--page-token`. The default
compact page contains at most four nodes. SQLite verifies one exact production
declaration row before reading aggregate impact data, counts the full result
separately, and orders pages by depth, source path, target identity, and edge
kind. Its stateless token carries only a query-bound offset up to 10,000.
Functions and properties return `IMPACT_OVERLOAD_GRANULARITY_UNAVAILABLE`
because the production index key cannot isolate same-file overloads; Kast does
not mislabel FQ-name aggregate rows as one callable's impact. Use
`--fields query,confidence` for metadata without nodes or `--count` for
cardinality only.

??? info "Command names for agent authors"
    The current typed agent commands are:

    - `kast agent lease acquire`
    - `kast agent lease status`
    - `kast agent lease release`
    - `kast agent verify`
    - `kast agent workspace-files`
    - `kast agent symbol`
    - `kast agent impact`
    - `kast agent diagnostics`
    - `kast agent rename`
    - `kast agent add-file`
    - `kast agent add-declaration`
    - `kast agent add-implementation`
    - `kast agent add-statement`
    - `kast agent replace-declaration`
    - `kast agent lsp`

??? info "Example agent execution"
    These examples are for agent authors and support workflows, not the normal
    developer install path.

    ```console
    kast agent lease acquire --workspace-root "$PWD"
    # Append --backend idea --lease-id <id> to every semantic command below.
    kast agent verify --workspace-root "$PWD" --backend idea --lease-id <id>
    kast agent workspace-files --kind source --workspace-root "$PWD"
    kast agent symbol --query OrderService --workspace-root "$PWD"
    kast agent symbol --query order --mode discovery --workspace-root "$PWD"
    kast agent symbol --query OrderService --explain --workspace-root "$PWD"
    kast agent impact \
      --symbol com.example.OrderService \
      --declaration-file src/main/kotlin/com/example/OrderService.kt \
      --declaration-start-offset 42 \
      --kind class \
      --count \
      --workspace-root "$PWD"
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
    kast agent lease release --workspace-root "$PWD" --lease-id <id>
    ```
