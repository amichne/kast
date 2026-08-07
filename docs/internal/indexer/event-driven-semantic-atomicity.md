# Event-driven semantic atomicity

Kast treats each workspace change as an invalidation signal. An event does not
describe semantic truth. One transition worker refreshes, reconciles, verifies,
and publishes that truth.

## Transition sequence

```text
READY -> DIRTY -> SETTLING -> REFRESHING -> RECONCILING -> VERIFYING
  ^                                                               |
  |                         PREPARE -> COMMIT ---------------------+
  |                                      \-> BLOCKED
  \---------------- exact published manifest ---------------------/
```

Each relevant VFS event closes semantic admission and adds one typed signal to
a bounded set. The worker waits for 250 ms of quiet time. It then drains the
set once. Ten thousand source events therefore request one source pass, not ten
thousand tasks.

The transition phases have these responsibilities:

1. **DIRTY** blocks new semantic reads and mutations.
2. **SETTLING** conflates an event burst. It does not refresh or reconcile.
3. **REFRESHING** refreshes the VFS. A build-semantic signal also requests a
   Gradle refresh and waits for the imported model to settle.
4. **RECONCILING** is the only phase that writes candidate semantic evidence.
5. **VERIFYING** computes the workspace-state identity (WSID) again. A changed
   WSID or a new event invalidates the pass.
6. **PREPARE** verifies completeness and binds the next logical revision while
   the workspace SQLite transaction remains uncommitted.
7. **COMMIT** writes the publication row and workspace facts in one transaction,
   then admits that exact revision as READY.
8. **BLOCKED** records a typed phase and reason. It does not replace the current
   published generation.

A five-minute recovery audit runs after a stable pass. It first withdraws
semantic admission, advances the admission revision, and waits for active read
leases and any mutation permit to drain. A VFS-only `RecoveryProbe` then checks
the exact current manifest, build inputs, live configuration, and WSID. If all
evidence is unchanged, a revision-bound token restores the same manifest as
READY without Gradle import, reconciliation, or publication. Drift or an
inspection failure schedules an explicit `RecoveryAudit` transition, which
does refresh Gradle and reconcile. A concurrent event invalidates the token and
cannot reopen READY. A VFS event that Kast did not receive therefore cannot
cause permanent stale readiness.

The worker also checks Git's exact-root transition markers after settlement,
around Gradle refresh, and at the publication boundary. Active checkout,
rebase, merge, cherry-pick, revert, sequencer, and index-lock state causes a
typed retry. A prepared candidate is discarded. This guard uses Git state only
to detect an incomplete filesystem transition. It does not use commit identity
as semantic truth.

## Signal boundary

The exact-root VFS listener classifies source, build-semantic, configuration,
scope, semantic-environment, and Git-worktree paths. A signal only wakes the
transition worker. A Git hook or commit identifier is not an authority. It
cannot represent dirty or untracked inputs, and it is not present for every
filesystem mutation.

Build-input hashing and VFS classification use the same path policy. It covers
Gradle and Groovy build logic, dependency lock files, `local.properties`, and
the supported root inputs. It excludes generated build output. Generated
Kotlin or Java under an imported compiler source root remains a source signal.
Only `.idea/compiler.xml`, `.idea/kotlinc.xml`, and `.idea/misc.xml` are
semantic-environment signals; unrelated IDE-local state is ignored. Java
annotation-processor artifacts and Kotlin compiler-plugin artifacts are watched
as classpath roots. Derived module compiler output is not a classpath authority.
The classpath resolver excludes module-source order entries, while retaining
library, SDK, compiler-plugin, and processor artifacts. This prevents a Gradle
import from observing its own `build/classes` output as a new semantic input.

VFS refresh occurs before each transition and recovery probe. `RecoveryProbe`
is VFS-only. A build-semantic or explicit `RecoveryAudit` signal refreshes the
linked Gradle project and waits for module and source-set import settlement. A
refresh failure closes admission and moves the transition to BLOCKED. Kast does
not use a foreground IDE project as a repair mechanism.

Initial reconciliation is also build-semantic. The public CLI therefore waits
up to six minutes for READY: five minutes for the bounded Gradle refresh and one
minute for process startup, model settlement, reconciliation, and publication.

Cold startup does not trust a compiler-ready module model from IntelliJ's
workspace cache by itself. After post-startup activities, a cache-backed open
waits for the sticky JPS project-loaded signal that follows real-state model
application. A fresh or discarded cache does not wait for a signal that
IntelliJ does not publish. Kast then accepts cached compiler readiness only if
the normalized Gradle root is registered as an exact linked project. Otherwise,
it links and imports the exact root and inspects the resulting model again.

## Workspace-state identity

WSID is a SHA-256 digest of all admitted semantic inputs:

- eligible Kotlin and Java paths and content, including admitted generated and
  untracked files;
- Gradle scripts, settings, properties, wrapper inputs, and version catalogs;
- the effective indexing scope;
- module names, source roots, resolved SDK type, canonical home, and version,
  unresolved SDK reference name and type, order-entry validity, non-derived
  class roots, and the classpath fingerprint;
- the effective Java language level, ordered compiler options, bytecode target,
  annotation-processing mode, processor paths and their content, processors,
  and processor options;
- the effective Kotlin facet settings, compiler arguments, compiler plugins,
  and plugin options.

The `.git` directory and commit identity are excluded. Two identical semantic
workspaces at different commits have the same WSID. A dirty or untracked
admitted source changes the WSID. An IntelliJ SDK presentation label is also
excluded when the resolved SDK semantics are unchanged. SDK identity has
distinct absent, resolved, and unresolved states.

## Single-database atomic publication

Each canonical workspace owns one `cache/source-index.db`. Before reconciliation,
`WorkspaceGenerationStore.begin` starts one SQLite write transaction. All source,
reference, semantic-graph, progress, and metadata mutations join that transaction;
their existing batch boundaries use savepoints rather than independent commits.
SQLite readers continue to see the prior committed snapshot while reconciliation
is in progress.

`WorkspaceGenerationStore.prepare` checks the uncommitted candidate before it can
become visible:

- at least one module has progress evidence;
- no module is incomplete;
- no unapplied update remains;
- the source-index schema and generation are current;
- the verified WSID is bound to the next positive logical revision.

The coordinator then captures WSID once more. A changed identity, new event,
failed phase, or cancellation discards the transaction. No candidate facts or
publication row become visible.

`WorkspaceGenerationStore.commit` rechecks the source-index generation and
expected revision, writes the singleton `workspace_publication` row, and commits
the transaction. The row binds logical revision, WSID, source-index generation,
schema version, publication time, and the optional overlay descriptor. This one
SQLite commit is the visibility boundary; Kast creates no workspace-generation
directory, immutable database copy, or pointer manifest.

Admission checks its reconciliation revision around commit. An event before
commit causes rollback. An event concurrent with a completed commit keeps READY
closed and schedules another pass; the committed database revision remains valid
and is the only current publication.

## Exact READY manifest and read leases

READY is `IdeaIndexSemanticAdmission.Status.Ready(manifest)`. It is not a
boolean. `runtime/status` exposes the same manifest as
`publishedWorkspaceGeneration`.

Each external semantic operation opens a read lease. The lease carries the
admission revision and the exact published manifest. It also increments the
active-reader count. The backend checks both values before it returns and then
closes the lease. A request fails with a conflict if it starts outside READY or
if its revision or manifest moves.

Reconciliation first withdraws READY. It then waits for all read leases and any
mutation permit to close. This order lets current reads finish against one
generation and prevents a writer from changing their evidence.

## Mutation permits

External mutations enter through `WorkspaceTransitionIngress`. A mutation can
start only from READY. The ingress records the current manifest, withdraws
admission, waits for readers and another mutation to finish, and grants one
mutation permit.

The permit covers only the filesystem or classification mutation. After the
operation, the ingress closes the permit, sends a typed signal, and waits for a
different READY manifest. The result does not return while the runtime still
advertises the pre-mutation generation.

This boundary covers edit application, exact file-image compare-and-swap,
mutation-scratch recovery, and external failure classification. A failed
mutation also releases the permit and requests source reconciliation.

## Cache-only public graph reads

The public semantic-graph operation does not write graph state. It reads the
published cache under a read lease. If the requested facts or removals require
work, the cache read returns an internal incomplete outcome before any write.

The public coordinator then requests a `RecoveryAudit` transition and waits for
a new published manifest. Only the internal reconciliation entry point receives
a reconciliation token and writes graph facts. The original public request
retries as a cache read against the new READY manifest.

Scope rejection remains a public read error. It does not request reconciliation
and does not change graph state.

## Restart recovery

`IndexerServerRuntime` opens the canonical `cache/source-index.db` directly.
SQLite journal recovery discards an interrupted transaction, so restart exposes
either the prior committed revision or the complete newer revision. Kast does
not copy a published database back into a mutable location.

If the database is absent, normal empty-store initialization or repository-base
overlay preparation proceeds. If its schema version is not current, Kast drops
and recreates owned tables, advances the source-index generation, and rebuilds
semantic state. It does not move, import, or serve the prior layout.

## Rust published-read boundary

Rust-local semantic readers resolve the exact root's one
`cache/source-index.db`. They reject a missing, symbolic, malformed, unpublished,
or schema-mismatched database.

The resolver reads `workspace_publication` and `schema_version` in one SQLite
transaction and requires their schema and source-index generations to agree. An
optional `repository-overlay.json` stays beside the workspace database and may
reference one absolute, immutable shared repository-base database. Local graph
coverage, repository queries, metrics, symbol queries, workspace inventory, and
default native-graph reads receive this resolved authority.

After a local semantic read, Rust resolves the publication row again, validates
the runtime descriptor, requests fresh runtime status, and compares the exact
logical manifest again. Database revision movement, runtime movement, or READY
withdrawal rejects the result. An explicit native-graph `--database` path remains
a diagnostic override and does not replace the default published-read contract.

## Executable guarantees

Focused tests cover event invalidation, a 10,000-event storm, moving-pass
rejection, an external disk edit through the production IntelliJ VFS listener,
and a real 1,000-file Git checkout held at its exact-root `index.lock`. They
also cover build refresh failure, stable recovery-probe no-op, missed-event
recovery, recovery-audit read draining and cancellation, SDK presentation-name
invariance, resolved and unresolved SDK identity sensitivity, Git-independent
identity, dirty and untracked identity, Java and Kotlin compiler identity,
cache-backed JPS reconciliation, fresh-cache bypass, late project-load
registration, exact Gradle-link admission, read-lease draining, mutation
exclusion, uncommitted-write invisibility, one-transaction publication,
rollback on invalidation, restart behavior, cache-only public graph coordination,
runtime-manifest identity, and Rust publication-row revalidation.

The [research ledger](research-ledger.md) maps each decision to repository
source, platform contracts, and executable proof.
