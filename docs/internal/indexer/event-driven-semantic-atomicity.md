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
6. **PREPARE** exports and validates one immutable generation. It does not make
   that generation visible.
7. **COMMIT** replaces `current.json` and admits that exact manifest as READY.
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

## Two-phase immutable publication

`WorkspaceGenerationStore.prepare` performs the slow phase. The source-index
writer stays closed to external reads while `VACUUM INTO` creates a consistent
database image. Preparation requires all of these facts:

- the source-index generation is the same before and after export;
- at least one module has progress evidence;
- no module is incomplete;
- no unapplied update remains;
- the database schema and source-index generation match the export evidence.

If repository overlay evidence exists, preparation copies its base database
into the same generation directory. It rewrites the overlay descriptor to that
contained base. Kast syncs the database, optional base, descriptor, and staging
directory. It then makes the files immutable and atomically moves the staging
directory to a unique generation directory.

The prepared value carries its expected `current.json` manifest. It is not
visible to readers. The coordinator checks its event counter after preparation.
If the workspace moved, it discards the prepared directory.

`WorkspaceGenerationStore.commit` performs the short visibility phase. It
revalidates the prepared files and compares the expected manifest with the
current manifest. A stale compare fails. A successful commit then:

1. writes the new manifest to a temporary pointer file;
2. syncs the temporary file;
3. atomically replaces `current.json`;
4. syncs the publication directory.

A failure before pointer replacement is a failed commit. A directory-sync
failure after pointer replacement is different: the new manifest is already
the visible generation. Commit returns a typed durability-warning result and
the runtime keeps that committed manifest. It never reports the prior manifest
as current and never discards the visible generation. READY status and
transition evidence retain the warning cause.

`current.json` is the visibility boundary. Its manifest binds the workspace
semantic generation, WSID, source-index generation, schema version, database
path, publication time, and optional overlay descriptor. The database path must
name `source-index.db` inside one generation directory. The overlay and its base
must remain inside that same directory.

Admission checks its reconciliation revision before and after pointer commit.
An event before commit prevents pointer replacement. An event during pointer
commit can leave a valid newer pointer, but Kast does not open READY for it. The
worker schedules another pass. Previous immutable generations remain intact.

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

`IndexerServerRuntime` recovers the mutable database before it constructs
`SqliteSourceIndexStore`. Recovery first removes the mutable database, overlay,
staging files, and stale `-wal` and `-shm` files. It then validates
`current.json` and every file that the manifest names.

If no pointer exists, recovery returns `NoPublishedGeneration`. The mutable
location stays empty, so repository snapshot preparation or normal empty-store
initialization can proceed.

If a valid pointer exists, recovery copies the exact published database and
optional overlay to staging files. It compares the copies byte for byte, checks
the database schema and source-index generation, and makes the database
writable. It checks `current.json` again, installs the overlay first, and uses
the database move as the commit point. It then syncs the mutable directory.

Recovery never changes `current.json` or its semantic generation. Any recovery
failure removes the mutable artifacts. Runtime startup therefore cannot fall
back to a partial live database. A bind-once exporter connects the generation
store to `SqliteSourceIndexStore` only after recovery and store construction.

## Rust published-read boundary

Rust-local semantic readers resolve `semantic-generations/current.json` for the
admitted exact root. They reject a missing or invalid pointer. They do not fall
back to the mutable `source-index.db`.

The resolver checks the manifest shape, schema, database generation,
containment, regular-file status, and optional contained overlay base. The
READY runtime status must advertise the same manifest. Local graph coverage,
repository queries, metrics, symbol queries, workspace inventory, and default
native-graph reads receive this resolved database value.

After a local semantic read, Rust resolves the pointer again, validates the
runtime descriptor, requests fresh runtime status, and compares the manifest
again. Pointer movement, runtime movement, or READY withdrawal rejects the
result. An explicit native-graph `--database` path remains a diagnostic override
and does not replace the default published-read contract.

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
exclusion, prepared-generation invisibility, pointer-last publication,
post-rename durability uncertainty, restart recovery, cache-only public graph
coordination, runtime-manifest identity, and Rust pointer revalidation.

The [research ledger](research-ledger.md) maps each decision to repository
source, platform contracts, and executable proof.
