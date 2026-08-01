---
type: Design Proposal
title: VFS-Resilient Headless Indexing
description: Proposed isolation, scope, recovery, and coverage contracts for durable graph and reference indexing on macOS.
tags: [internal, design, macos, headless, indexing, semantic-graph, references, vfs]
code_sources:
  - path: cli-rs/src/execution/runtime/backend/workspace.rs
  - path: cli-rs/src/execution/runtime/backend/descriptors.rs
  - path: cli-rs/src/configuration/config/model.rs
  - path: cli-rs/src/configuration/config/workspace/mutation.rs
  - path: cli-rs/src/agent/adapter/graph.rs
  - path: cli-rs/src/agent/navigation/native_graph/source_scope.rs
  - path: cli-rs/src/semantics/repository_intelligence/coverage/model.rs
  - path: cli-rs/src/semantics/repository_intelligence/coverage/read.rs
  - path: backend-idea/src/main/kotlin/io/github/amichne/kast/idea/backend/semantic/SemanticGraphOperations.kt
  - path: analysis-server/src/main/kotlin/io/github/amichne/kast/server/dispatch/RpcAnalysisDispatcher.kt
  - path: backend-idea/src/main/kotlin/io/github/amichne/kast/idea/workspace/indexing/IdeaProjectIndexer.kt
  - path: backend-headless/build.gradle.kts
  - path: backend-headless/src/main/scripts/kast-headless
  - path: index-store/src/main/kotlin/io/github/amichne/kast/indexstore/store/sqlite/stage/FileStageInventoryStore.kt
  - path: index-store/src/main/kotlin/io/github/amichne/kast/indexstore/indexing/ReferenceIndexer.kt
  - path: index-store/src/main/kotlin/io/github/amichne/kast/indexstore/store/SqliteSourceIndexStore.kt
  - path: .github/scripts/release/actions/build-setup-bundle/action.yml
  - path: cli-rs/src/operations/daemon.rs
  - path: scripts/packaging/package-headless-runtime.sh
  - path: install.sh
---

# VFS-Resilient Headless Indexing

**Status:** Proposed for review. The sections from **Proposed ownership** through
**Migration** are normative if this proposal is accepted.

**Audience:** Maintainers of the Kast runtime, index store, CLI, IDEA plugin,
headless backend, and release packages.

This proposal separates durable indexing from the interactive IDEA virtual file
system. It also makes partial graph and reference evidence useful without
claiming complete results.

## Problem

Users observe repeated incomplete graph failures when interactive IDEA virtual
file system activity cancels or invalidates graph and reference work. This is a
reported production symptom. This proposal does not claim a local reproduction.

The current implementation has nine relevant constraints:

1. macOS rejects explicit local headless runtime selection.
2. Semantic graph refresh runs in the requesting coroutine and checks both
   coroutine cancellation and IDEA progress cancellation.
3. Native graph source selection requires complete relationship indexing even
   though graph extraction reads PSI and Kotlin compiler facts directly.
4. Native graph reads reject globally incomplete persisted graph evidence.
5. Release setup bundles contain the headless backend, but the normal macOS
   installer downloads only the native CLI and IDEA plugin. It does not install
   or activate the bundled headless component.
6. The headless backend archive has no Java runtime. Its launchers select
   `JAVA_HOME` or `java` from `PATH`, so it is not a self-contained macOS
   sidecar.
7. Normal macOS setup disables public headless-backend selection, and the CLI
   process that starts headless does not remain as a supervisor.
8. Headless loads one configuration snapshot at startup, while semantic graph
   refresh still routes to the selected IDEA runtime and writes there.
9. The release builds one Linux headless archive with native IDEA libraries and
   reuses that archive in macOS setup bundles.

These constraints couple graph availability to interactive IDEA work and to a
separate reference-index stage. One cancelled or failed file can therefore make
all native graph operations unavailable.

## Goals

- Isolate durable graph and reference indexing from the interactive IDEA
  virtual file system.
- Preserve committed batches across cancellation, restart, and configuration
  changes.
- Let non-critical gaps produce qualified evidence instead of a global failure.
- Let a workspace opt into strict coverage for configured critical paths.
- Exclude generated output before it reaches any source-taking Kast operation.
- Apply indexing scope and batch-size changes without a runtime restart.
- Keep runtime readiness separate from graph and reference coverage.
- Start the managed sidecar without system Java or the interactive IDE runtime.

## Non-goals

- Merge unsaved IDEA document content into the persisted index.
- Add readiness rules for individual graph or reference operations.
- Let IDEA and headless backends write the same index.
- Replace generation-pinned SQLite reads.
- Add graph parallelism controls.
- Change the semantic implementation of focused IDEA analysis for admitted
  source paths.

## Proposed ownership

On macOS, the CLI bootstraps or reuses the exact-root IDEA project and returns.
The resident IDEA project service then manages one exact-root headless index
sidecar. The sidecar runs in a separate JVM with a separate virtual file system.
The macOS setup bundle combines the release-matched headless component with an
architecture-matched Java 21 runtime. The normal macOS install path installs
that self-contained sidecar.

```mermaid
flowchart LR
    bootstrap["CLI exact-root bootstrap"] --> supervisor["Resident IDEA project service"]
    supervisor --> live["Focused live IDEA analysis"]
    supervisor --> headless["Headless index sidecar"]
    config["Workspace TOML and .kastignore"] --> headless
    disk["Saved workspace files"] --> headless
    headless --> store["SQLite source index"]
    store --> queries["Persisted graph and reference queries"]
    supervisor -. "read persisted evidence" .-> store
```

The headless sidecar is the sole persistent writer. It indexes saved disk state
only. The IDEA backend keeps focused live analysis and may read persisted
evidence. It does not perform persistent graph or reference writes.

The managed sidecar starts automatically with the exact-root IDEA runtime even
when public `backends.headless.enabled` is `false`. This proposal makes that
setting authoritative for explicit standalone headless selection. When it is
`false`, automatic semantic backend selection excludes public headless
descriptors and a new explicit start or semantic request fails with
`HEADLESS_BACKEND_DISABLED`. Read-only lifecycle inspection and explicit stop
can still discover an existing standalone descriptor, so a live process is not
stranded when the setting changes. When it is `true`, the existing standalone
path remains available subject to the exact-root writer lease. The setting does
not gate the internal sidecar role. The sidecar remains available until the
resident project service stops.

If that setting becomes `false` while a standalone runtime owns the writer
lease, the standalone fences persisted admission and rejects new semantic work.
It remains reachable only for lifecycle inspection, configuration forwarding,
drain, and explicit stop. The resident service does not replace it while its
writer lease is live. A managed sidecar can start after explicit stop or
confirmed standalone process death releases the lease.

Managed and standalone headless roles use the same exact-root writer lock and
admission-record locations. The first role to hold the writer lease remains the
sole writer; there is no automatic lock stealing. A standalone start while the
managed sidecar holds the lease fails with a typed writer-conflict error that
names the managed incumbent. If a standalone runtime already holds the lease,
the resident service does not start a second sidecar; persisted readers continue
to use the incumbent's admission endpoint and global coverage only while public
headless selection remains enabled. The service watches the standalone control
endpoint and writer lease as well as explicit drain and stop. After a confirmed
process death makes the endpoint unreachable and releases the lease, it uses
the existing stale-descriptor and admission-record cleanup, rechecks host
claims, and can acquire the released lock for a managed sidecar.
Switching in the other direction requires the resident project service to drain
and stop its managed sidecar before standalone startup can acquire the lease.

The internal sidecar does not publish a public runtime descriptor, register as
a semantic backend, or participate in automatic backend selection. The
exact-root IDEA runtime remains the public backend candidate. Explicit
standalone headless selection keeps its existing descriptor and discovery path.

The resident IDEA project service supervises the sidecar when it owns the
managed writer role. A managed sidecar crash does not stop the IDEA runtime.
The service reports unavailable graph and reference coverage, preserves the
last committed generation, and restarts the managed sidecar with bounded
backoff. It does not supervise or restart a standalone process; the confirmed
crash handoff above starts a new managed role under a new writer lease.

The `index-store` exact-root coordination authority owns the writer-lease and
host-claim paths, schema, compare-and-set rules, and monotonic generation. IDEA
and Android Studio project services register live claims through that authority;
the Rust bootstrap, managed sidecar, and standalone sidecar are clients. Managed
startup requires exactly one claim and binds the supervisor lease to that claim
identity and generation. The generation advances when the live claim-identity
set changes, not when an unchanged claim renews its heartbeat.

That authority resolves one immutable `<coordination-root>` from the normalized
canonical workspace root before it resolves any physical database. It is stable
across sidecar releases, schema versions, and active-store identities, and is
never derived from `dirname(resolvedPhysicalDatabasePath)`. The writer lock,
host claims, writer lease, configuration-root binding, store-preparation ledger
and receipts, active-store locator, admission record and socket, and `stores/`
directory all live below this owner-only root. Every contender and reader
therefore discovers the same coordination state before it knows which database
family is active.

Writer acquisition atomically publishes a role-tagged provisional lease record
before endpoint startup. It binds the canonical root, `MANAGED` or `STANDALONE`
role, release, instance, process identity, lease epoch, and expected
configuration-root identity and epoch. Endpoint publication promotes that same
record to active by compare-and-set. A competing start that observes a live
provisional record waits for bounded publication and then returns the typed
incumbent conflict; if the process and lock are both gone, it can clear the
expired record and retry. No start can observe an unidentified writer-lock
owner.

The service and sidecar both watch the authority. When a second supported host
claims the root, the authority records ambiguity before the second service can
start a sidecar. A running managed sidecar stops admitting reads and work,
discards its uncommitted unit, releases the writer lock, and exits; the resident
service does not restart it. Both host runtimes report graph and reference
coverage unavailable, and bootstrap returns `IDEA_HOST_AMBIGUOUS`. When one live
claim remains, that service can start a new managed sidecar only after the old
writer lock is free.

The admission barrier synchronously reads the coordination authority; it does
not rely on delivery of the watch notification. A managed sidecar treats any
claim-identity or generation change as terminal. A standalone incumbent is not
bound to one host claim: on a non-ambiguous zero-to-one, one-to-zero, or
single-host replacement it rejects old tokens, discards its uncommitted unit,
and rebinds to the new generation. It pauses admission and work while claims are
ambiguous, then rebinds when the authority becomes non-ambiguous.

Before a sidecar commits a batch, it acquires a shared host-claim commit guard,
revalidates the permitted generation, and holds the guard through the SQLite
commit. Claim publication requires the incompatible exclusive guard. A claim
therefore publishes either before validation and rejects the batch, or after a
completed batch; it cannot appear between validation and commit.

The sidecar exposes an exact-root read-admission endpoint from the immutable
coordination root.
Every persisted graph and reference reader checks this endpoint before and
after a generation-pinned SQLite read. Its typed admission response separates
an immutable read fence from progress at one store generation. The read fence
binds the sidecar instance, writer-lease epoch, active-store identity,
configuration-root binding identity and epoch, scope fingerprint, project-model
semantic fingerprint, filesystem event epoch, host-claim generation,
resolved-configuration fingerprint, and committed critical-path
acceptance-ledger generation. The progress snapshot binds store generation,
evidence-family state, counts, and limitations.

The reader opens one SQLite read transaction and verifies that its facts and
coverage metadata have the progress snapshot's generation. Facts, state,
counts, and limitations in the result all come from that one pinned snapshot.
Post-read validation succeeds when the read fence is unchanged and the live
store generation is greater than or equal to the pinned generation. A later
same-fence batch can therefore commit without starving the read. A lower live
generation, a missing endpoint, or any changed fence identity rejects the read.
The active-store identity must still equal the committed locator before and
after the read. SQLite coverage alone never admits persisted evidence. This
authority is separate from IDEA runtime readiness.

This proposal supersedes only the post-read live-generation equality clause in
the current [stable integration invariant](index.md#stable-integration-invariants)
and accepted
[generation decision](architecture-decisions.md#pin-every-native-graph-query-to-one-generation).
Before target-schema sidecar admission is active, those exact-generation rules
remain authoritative. After cutover, advancement from `g` to `g+n` is permitted
only for one already-open SQLite transaction under an unchanged read fence, and
the complete result remains pinned to `g`. A new page using a cursor issued at
`g` still requires live generation `g`; refresh planning and write
compare-and-set still reject `expectedGeneration = g` after any advance; and no
reader can mix generations. The implementation change that enables the new
admission protocol must update those two accepted records and the dependent
generation descriptions in `flows/graph-queries.md` and
`flows/indexing-and-generation.md` in the same cutover. Legacy readers and
deployments without the target admission protocol continue to reject every
generation change; no deployment mixes the two rules.

The private admission record is `<coordination-root>/admission.json`; its
default endpoint is `<coordination-root>/admission.sock`. When that
Unix-domain-socket path
exceeds Kast's existing 100-byte safe threshold, the shared workspace-path
resolver selects its deterministic short hashed socket path. The admission
record always contains the actual endpoint. The CLI derives these locations
from the same database and workspace-path authorities; it does not use backend
discovery.

The record is role-tagged and copies the canonical-root digest, release,
instance, role, and writer-lease epoch from the coordination record. It also
contains the active-store identity, resolved physical database path, socket
path, and admission protocol. A managed record adds its supervisor lease and
bound host-claim identity; a standalone record has no supervisor lease. The
standalone public descriptor publishes the same common identity plus its control
endpoint and protocol. Adoption requires the public descriptor, private
admission record, and live writer lease to agree exactly. A reader opens only
the database path named by the validated record. Before opening it, the reader
requires the record's normalized path and active-store identity to equal both
the committed locator and the pre-read admission fence. The post-read check
revalidates the same locator and fence identity.

After store preparation selects or activates the canonical target, the sidecar
opens that store and reconciles current inputs. When the reconciliation commits,
it atomically publishes the admission record and promotes its provisional writer
lease. The directory and private files are owner-only. A reader validates the
owner, root digest, release, role, instance, lease epoch, active-store identity,
normalized database path, and protocol before it connects. Graceful exit
unlinks `admission.json` and the socket node. After a crash, the resident service
or existing standalone lifecycle cleanup can unlink stale records only after the
endpoint is unreachable and the writer lock is free. A replacement sidecar then
publishes a new identity.

Before it issues or revalidates a token, the sidecar performs a synchronous
filesystem event barrier on its own platform watcher. It asks each native event
producer for a sequence fence and waits until the watcher consumes the marker
that follows every earlier filesystem mutation; draining the current queue is
not sufficient. It then hashes affected compiler inputs, rescans affected
compiler-input roots, and commits fingerprint invalidation before it advances
the event epoch and replies. A save completed before the fence request is
therefore processed before admission. The post-read check takes a second
producer fence; a save during the SQLite read changes the epoch and rejects that
result.

If a producer cannot prove the fence, reports dropped or coalesced history, or
crosses a mount generation, the sidecar compares a complete deterministic
snapshot of the watched input set with its committed Merkle root. It issues no
token when that comparison cannot complete. This barrier does not use the
interactive IDEA virtual file system.

The watch set is independent of source admission. It covers every compiler
input in a semantic cohort and every filesystem model input recorded by the
last complete Gradle import, including inputs outside admitted roots or an
external included build. Model-input provenance includes custom files read
during configuration, not only known Gradle filenames. If the import cannot
provide complete provenance or the sidecar cannot watch an input, both evidence
families remain `UNAVAILABLE`.

A model-affecting saved input includes any file in that provenance. Gradle build
and settings scripts, `gradle.properties`, version catalogs, `buildSrc`, and
convention-plugin sources are common examples. When the barrier observes one,
it atomically marks the project model stale and both evidence families
`UNAVAILABLE` before it can reply. The sidecar requests a model refresh and
issues no admission token until a completed model snapshot is reconciled and
its fingerprint is committed. Existing facts remain stored but cannot be
served against the old model.

When the service starts a managed sidecar, it establishes an exact-root,
lease-bound `SUPERVISOR` control channel. Persistent graph and reference refresh
requests enqueue an evidence-family refresh through that channel; the IDEA
request handler no longer performs the write.

A standalone runtime exposes the same release-matched control protocol through
its existing public runtime endpoint. When it is the writer incumbent, the
resident service validates the public descriptor, private admission record,
and live writer-lease identity. It then connects with a non-supervising `CLIENT` role.
Refresh requests and configuration event or mutation notifications use that
channel. Client disconnect does not terminate or transfer ownership of the
standalone process. An incompatible incumbent rejects adoption with a typed
control-protocol error and remains the writer until explicit stop; persisted
admission stays unavailable after a configuration fence that the incumbent
cannot reconcile.

Rust remains the sole TOML, `.kastignore`, and configuration-precedence parsing
authority. Before exact-root host launch or reuse, the release-matched Rust
bootstrap resolves the global configuration root from the initiating
invocation's explicit `KAST_CONFIG_HOME` or the installed resolver's default. It
canonicalizes that root and its `config.toml` path, then atomically commits an
owner-only exact-root `ConfigurationRootBinding` below the coordination root
before bootstrap completes. The binding contains the exact-root, install and
release identities, canonical root and file path, resolution source, and a
monotonic binding epoch. It contains no secret. A direct interactive host open
without new CLI provenance reuses a valid committed binding. Only when no
binding exists does it invoke the release-matched Rust helper to commit the
installed default. A present but invalid or stale-release binding fails typed
until an explicit Rust bootstrap replaces it; the resident service never
substitutes its environment or silently resets the root.

Each writer lease captures one committed binding identity and epoch. The
resident service reads it and forwards it in managed startup and every
lease-bound `SUPERVISOR` message. Standalone startup publishes the same binding,
and later resident `CLIENT` adoption must use the incumbent's binding. A crash
restart or host reuse reloads the durable binding. No producer or sidecar
re-evaluates `HOME`, `KAST_CONFIG_HOME`, or any host-process environment.

The binding is immutable for one live writer lease. A later bootstrap that
resolves a different root returns a typed configuration-root conflict and
requires an explicit writer drain and new bootstrap lease before it can commit a
higher binding epoch. It never silently moves the watcher. This restriction does
not delay live reload of file changes or collection mutations within the bound
root. A bootstrap that resolves the identical root, file, source, install, and
release identity is an idempotent no-op; it retains the binding identity and
epoch and does not fence the lease.

The bootstrap request carries its expected binding identity and epoch. Rust
holds the exact-root configuration transaction lock from the binding
compare-and-set through host launch or reuse and the acknowledgment that the
provisional or reused active writer lease captured that same tuple. Writer
acquisition rejects a mismatch and never adopts a binding committed by another
request. A competing different-root bootstrap waits for the lock and then
observes the incumbent lease or returns the typed bootstrap conflict; it cannot
replace the pending request's binding. Process death releases the lock, and
recovery validates any unleased record before it retries.

A configuration mutation projects and sends one complete resolved JSON
snapshot through the control channel. For external edits, there is one
projection producer for each writer-lease epoch. The resident service is that
producer for a managed writer. A standalone control adapter is the producer for
the full lifetime of a standalone lease, whether or not a resident `CLIENT` is
connected. An adopted resident forwards event and mutation notifications; it
does not start a competing projection helper.

The active producer watches exactly `<bound-configuration-root>/config.toml`,
the workspace TOML, and `.kastignore`, then invokes the release-matched Rust
projection with the explicit binding and forwards its snapshot. The sidecar
also watches those raw inputs, the binding record, and their parent directories
only to fence create, replace, save, and delete events; it does not parse them or
recompute precedence. Such an event makes configuration pending and both
evidence families `UNAVAILABLE` before the admission barrier replies. The Rust
snapshot carries the binding identity and epoch, canonical input paths, content
digests, and a resolved configuration fingerprint. The sidecar rejects a
missing, stale, wrong-root, wrong-release, or path-mismatched binding without
falling back to its environment. It issues no token until the binding matches
the committed bootstrap record, the input digests match its barrier view, and
scope and coverage reconciliation persists that binding identity and epoch with
the new configuration fingerprint and commits the generation. `analysis-api`
owns the binding and snapshot schema, including the new ignored and critical
collections and the Android selected-variant map.

Direct mutations and watcher-triggered projections serialize under one
Rust-owned exact-root configuration transaction lock. After it acquires the
lock, the helper rereads every raw input and the acceptance ledger. Every
control message binds the writer-lease epoch; a writer cutover rejects the old
transaction and retries under the new writer. The lock covers the complete
critical-path candidate, inventory proof, `PENDING` receipt, store
acknowledgment, `COMMITTED` receipt, and revocation-tombstone exchanges below.
Only a repeated tuple of configuration-root binding epoch, resolved
configuration fingerprint, and ledger generation is an idempotent no-op. A
standalone lease therefore needs no projection-producer handoff when a resident
connects. A control message from an older binding epoch is rejected.

Shutdown sends drain and waits for acknowledgment before the service closes
shared state. Request cancellation can stop waiting for an acknowledgment, but
it cannot cancel work after the sidecar accepts it.

The service is the sole holder of a managed channel's supervisor end. A managed
sidecar treats supervisor EOF or lease expiry as terminal. It stops accepting
work, discards any uncommitted unit, closes the store, releases the writer lock,
and exits. Lease expiry is the fallback when process death does not deliver EOF.
A replacement service waits for that lock release before it starts a new
sidecar. A standalone `CLIENT` channel has no supervisor semantics.

## Source admission

A model-owned candidate is a canonical exact-root Kotlin source file that the
complete headless IDEA and Gradle model assigns to a source set. A symbolic link
must resolve inside the exact root. An unowned file is not a candidate.

If the project model is missing or stale, the worker does not reconcile the
store. It preserves the last committed generation and reports graph and
reference coverage as `UNAVAILABLE` until the model is complete.

The worker computes one effective source set in this order:

1. Normalize the candidate under the exact workspace root.
2. Reject hard-excluded output.
3. Detect ignore-critical overlap against the remaining model-owned candidates.
4. Apply repository and workspace ignore rules.
5. Classify the remaining path as critical or non-critical.
6. Attach Gradle module and source-set ownership.

A candidate that completes all six steps is an eligible file.

### Android project model

An Android module is one for which the complete Gradle import reports the
Android Gradle Plugin. A generic Gradle source-root list is not a complete model
for that module. The bundled Android/AGP model provider runs inside the
sidecar's JVM and virtual file system and imports saved disk state itself. It
does not consume the interactive Android Studio project model or virtual file
system.

Each Android module has one selected production variant. The machine-local
workspace TOML can set `indexing.android.selectedVariants` as a map from
build-qualified Gradle project identity to variant name. The identity combines
the stable canonical Gradle build identity with its project path, so repeated
`:app` paths in composite builds do not collide. Without an entry, the provider
selects a variant named exactly `debug`; if no such variant exists, it selects
the sole available variant. Multiple remaining variants produce the typed
`ANDROID_VARIANT_SELECTION_REQUIRED` limitation. A configured name that the
AGP model does not expose produces `ANDROID_VARIANT_UNKNOWN`. A setting change
fences admission and requires a new model import.

A complete Android model records the model-provider identity, AGP version,
selected variant, source-provider ownership, compiler inputs, and compiler
cohort for every Android module. These values contribute to the project-model
and stage-input fingerprints. Non-generated Kotlin roots from the selected
variant's `main`, individual-flavor, combined-flavor, build-type, and full
variant source providers are model-owned candidates. Its test-fixture,
unit-test, and instrumented-test providers keep their corresponding
non-production priority. Model-declared generated and output roots remain
hard-excluded; their classpath and ABI inputs still contribute to the semantic
cohort.

If the Android provider is absent, stale, incompatible with the AGP model, or
cannot produce the selected-variant ownership, both evidence families are
`UNAVAILABLE` with limitation `PROJECT_MODEL_CAPABILITY_UNAVAILABLE`. Generic
Gradle ownership is not a fallback, and an empty Android inventory cannot be
classified as `COMPLETE`.

### Hard exclusions

Hard exclusions apply to source inventory, graph, reference, diagnostics,
symbol, and mutation path admission. Commands that do not admit a source path
are unaffected. No configuration can re-include a hard-excluded path.

The hard-exclusion authority combines:

- Gradle and IDEA model output or excluded roots;
- `.gradle` cache roots owned by the workspace or a declared Gradle build;
- the `build` output root of each declared Gradle project and the `out` output
  root of each IDEA content root;
- built plugin output below those roots.

One shared hard-exclusion authority accepts a canonical exact-root path and the
complete current product-model exclusion set. It resolves symbolic-link aliases
and returns either `AdmittedSourcePath`, bound to the exact root, project-model
generation, exclusion fingerprint, resolved canonical path, path-resolution
identity, and filesystem event epoch, or the typed `SOURCE_PATH_HARD_EXCLUDED`
result with its reason and model provenance. Source-set ownership, critical
configuration, and ignore negation cannot override an explicit output,
excluded, or generated root.

Source inventory and every source-taking graph, reference, diagnostics, symbol,
and mutation transport and entry point consume that decision before downstream
filesystem, PSI, compiler, persisted-store, planning, or mutation work. No
consumer reimplements the rules, admits a fallback path, or overrides the
rejection. Rust consumers use the serialized decision and do not reclassify the
path, but the receiving authority validates every serialized field against
current state before it constructs the strong type. A model-generation,
exclusion-fingerprint, path-resolution, or filesystem-event change invalidates
an admitted value. Consumers use only the resolved canonical target carried by
that value, never the caller's symbolic-link alias. Mutation commits through an
identity-checked target and parent handle, so a retarget or replacement cannot
redirect the write after validation.

A source-taking read uses an identity-bound, no-follow handle when its API can
consume one. A PSI or compiler API that requires a path takes a synchronous
native filesystem producer fence after its read, resolves that canonical target
again without following the caller alias, and requires the path-resolution
identity and filesystem event epoch to match the admitted value. It discards the
result with a typed stale-admission error when either check changes or the
producer cannot prove the fence. An external retarget or replacement therefore
cannot redirect a read or return evidence for a newly hard-excluded target.

Product-model and exclusion publication requires an exclusive source-admission
guard. Each source-taking operation acquires the compatible shared guard,
validates its `AdmittedSourcePath` under that guard, and holds it through its PSI
or compiler read epoch, persisted batch commit, or filesystem mutation commit.
Publication and the operation therefore have one linear order; a symbolic-link
retarget or newly excluded root cannot appear between validation and use.
Rejection performs no PSI scan, store write or generation advance, or filesystem
mutation. User ignore rules remain a separate graph-and-reference scope control.

A directory name alone does not prove that a path is output. An explicit Gradle
or IDEA output or excluded root always wins over any nested source root. If no
explicit output root covers the path, a more-specific independent Gradle build
root is classified before the conventional name of its parent directory. For
example, an included build rooted at `build/` can admit
`build/src/main/kotlin` only when the parent model does not mark `build/` as
output; that build's own `build/build/` output remains hard-excluded. This rule
prevents generated classes, packaged plugins, sandboxes, and copied sources
from entering semantic operations without rejecting an independent build by
name alone.

### User ignore rules

The repository root can contain `.kastignore`. Its path separator is `/`. A
leading slash is an anchor token, not a filesystem-root marker. Exactly one
leading slash anchors a pattern at the workspace root. A trailing slash matches
directories. `**` matches across directory boundaries. `#` starts a comment,
`!` re-includes a prior match, and the last matching pattern wins. Hard
exclusions still win over negation. URI schemes, drive or volume prefixes,
network-root prefixes with two leading slashes, and parent traversal are
invalid.

The machine-local workspace TOML can add `indexing.ignoredPaths`. Repository
and workspace ignore rules affect graph and reference indexing only. They do
not change focused diagnostics, mutation, or other interactive analysis.
The effective ignore set is the union of the final `.kastignore` result and
all matching workspace ignore patterns. A `.kastignore` negation cannot
re-include a workspace-ignored path.

All effective configuration inputs and `.kastignore` are watched. A valid
change reconciles the persisted inventory without a restart. Newly ignored
files lose current graph and reference facts. Newly admitted files become
pending.

### Critical paths

The machine-local workspace TOML owns one shared
`indexing.criticalPaths` collection. It applies to graph and reference
coverage. The default collection is empty.

Workspace ignored and critical collections accept positive repository-relative
patterns. They use the same anchoring, directory, and wildcard rules as
`.kastignore`, but do not accept comments or negation.

A model-owned candidate that survives hard exclusion cannot match both ignored
and critical rules. Conflict validation uses the post-hard-exclusion,
pre-ignore candidate inventory, not abstract pattern intersection or the final
filtered inventory. A future file that creates a conflict makes the new
inventory invalid and leaves the last valid physical scope active. Both graph
and reference coverage immediately become `INCOMPLETE` with limitation
`IGNORE_CRITICAL_CONFLICT`; persisted-evidence operations remain rejected until
the conflict is resolved. Existing committed facts are not deleted by this
validation failure.

Adding a critical pattern that matches no current eligible file is a typed
configuration error. After a pattern is accepted, deleting or renaming its
last match does not invalidate the configuration. It makes graph and reference
coverage `INCOMPLETE` with a typed unmatched-critical-path limitation until a
file matches again or the pattern is removed.

The Rust workspace mutation authority keeps a critical-path acceptance ledger
beside the machine-local workspace TOML, outside both raw user configuration
and the rebuildable index store. Each Rust-issued receipt binds a normalized
pattern to the source-inventory fingerprint under which it first matched, the
exact resolved-configuration fingerprint, and a ledger generation. A new CLI or
external-edit pattern remains pending behind the configuration admission fence.

Rust first forwards a typed candidate snapshot through the control channel. The
sidecar drains its filesystem barrier and uses its current eligible inventory to
return either an unmatched error or an inventory proof bound to the sidecar
instance, candidate configuration fingerprint, model fingerprint, filesystem
event epoch, and store generation. Rust does not recompute eligibility. It
durably records a `PENDING` receipt only from that proof, then forwards the
prepared resolved snapshot and ledger generation. The sidecar revalidates every
proof binding, commits the prepared ledger generation with the scope
fingerprint, and returns that durable store commit as acknowledgment. A changed
binding makes Rust abort the pending receipt and restart the exchange.

Rust promotes a receipt to `COMMITTED` only after that acknowledgment; this
promotion is the acceptance linearization point. It then forwards the committed
ledger state. The sidecar issues no admission token until its prepared scope and
the Rust `COMMITTED` generation agree. After a crash, Rust promotes a pending
receipt only when the store still proves its prepared commit. Otherwise it
aborts and reproves the candidate. A cold rebuild reapplies only committed
receipts; it reproves pending receipts instead of treating them as acceptance.

Removal atomically replaces the receipt with a durable revocation tombstone
before Rust forwards the snapshot that omits the pattern. The tombstone survives
restart and remains until a future current match produces a new receipt. After a
crash, Rust replays the pending ledger generation, and admission stays blocked
until the ledger, resolved configuration, and committed scope generations agree.
Only the Rust authority can issue or revoke receipts. A missing database or cold
schema rebuild therefore preserves the distinction between a rejected new
unmatched pattern and an accepted obligation that later became unmatched.

Each accepted pattern is a critical obligation. The obligation is fulfilled
only while it matches at least one eligible file. Every file matched by a
fulfilled obligation is critical. Deletion, hard exclusion, or loss of source
ownership can therefore make an accepted obligation unmatched.

If a new live configuration snapshot is syntactically invalid, the worker keeps
the last valid scope and reports the error. It does not apply a partial
configuration and does not stop. A resolved ignore-critical conflict follows
the `INCOMPLETE` rule above even while the last physical scope remains active.

The CLI provides idempotent collection mutations:

```shell
kast config add indexing.criticalPaths 'module/src/main/**' --workspace-root "$PWD"
kast config remove indexing.criticalPaths 'module/src/main/**' --workspace-root "$PWD"
kast config add indexing.ignoredPaths 'samples/**' --workspace-root "$PWD"
kast config remove indexing.ignoredPaths 'samples/**' --workspace-root "$PWD"
```

Adding an existing value and removing an absent value are successful no-ops.
Malformed patterns, URI schemes, drive or volume prefixes, network-root
prefixes, parent traversal, and ignore-critical conflicts fail with typed
configuration errors.

## Work order

Graph and reference indexing are independent pipelines. Graph work does not
wait for relationship outcomes. Reference work does not wait for graph
outcomes.

Semantic graph pending-work selection, source progress, coverage fingerprints,
and coverage classification must not read relationship-stage terminality.
Reference-derived topology remains reference evidence; removing these gates
does not make that topology independent of reference indexing.

Each pipeline uses this stable priority order:

1. critical paths;
2. `main` source sets;
3. `testFixtures` source sets;
4. `test` source sets;
5. the existing module-priority order;
6. normalized path order.

For Android modules, selected production-variant providers occupy step 2,
Android test fixtures occupy step 3, and unit or instrumented test providers
occupy step 4.

The worker uses bounded batches and short SQLite transactions. Existing
`indexing.relationships.enabled`, `indexing.relationships.batchSize`,
`indexing.relationships.parallelism`, and
`indexing.relationships.modulePriorityDepth` settings remain authoritative for
references. Module priority depth computes step 5 only for work with an
active-module anchor. An explicit saved-path refresh uses the owning module as
that anchor. Startup, file-watch reconciliation, and whole-workspace refresh
have no anchor and skip step 5; they continue with normalized path order. A new
positive integer `indexing.graph.batchSize` replaces the hard-coded semantic
graph batch size. The worker reloads these values with the other watched scope
settings.

When `indexing.relationships.enabled` changes to `false`, the worker discards
uncommitted reference work and schedules no new reference work. It retains
committed reference facts but does not serve them. Reference coverage becomes
`UNAVAILABLE` with limitation `REFERENCE_INDEXING_DISABLED`; graph scheduling
and graph coverage do not change. When the setting changes to `true`, the
worker schedules current reference work from the persisted inventory and
normal reference coverage computation resumes.

## Cancellation and retry

Cancellation is a scheduling result, not a file failure. The worker stops the
current uncommitted unit, keeps it pending, and retries it with bounded
backoff. Earlier committed batches remain available.

Three consecutive non-cancellation failures for one complete stage input
fingerprint make that stage `LIMITED`. The fingerprint includes source content,
admitted membership and ownership, project model, and semantic cohort. The
worker retries limited work at a slower periodic interval. A change to any
fingerprint component or an explicit refresh starts a new failure sequence and
makes that work immediately eligible again.

Only facts or boundary evidence tied to the complete current stage input
fingerprint and a current `COMPLETE`, `LIMITED`, or `EXTERNAL_BOUNDARY` outcome
are queryable. Prior facts for any changed input are stale and cannot appear in
a qualified result.

A reference `EXTERNAL_BOUNDARY` remains a distinct terminal outcome for its
complete current stage input fingerprint. It preserves the externalized failure
identity and exposes the existing limited unknown boundary; it does not migrate
to `LIMITED`. It is re-evaluated after that fingerprint changes, or after
explicit refresh. On a critical file it makes reference coverage `INCOMPLETE`.
On a non-critical file it makes reference coverage `QUALIFIED`. It does not
affect semantic graph coverage.

The retry policy has one owner in the workspace worker. Request handlers do not
implement their own retry loops.

## Global coverage

Runtime readiness, graph coverage, and reference coverage are three separate
facts. One cannot imply another.

Every existing graph or reference semantic operation that consumes persisted
evidence first requires the exact-root public runtime to be `READY`. A runtime
failure returns the existing typed runtime error; it does not become a coverage
error. Only then does the operation evaluate graph or reference coverage.
Runtime `INDEXING` remains a typed blocker even when a retained sidecar
generation reports `COMPLETE`, and sidecar availability never synthesizes
runtime `READY`. This is one global runtime gate followed by one global state
per evidence family; it does not add a readiness state for each command or
operation.

Graph and reference pipelines each publish one global coverage state:

| State | Meaning | Operation behavior |
| --- | --- | --- |
| `COMPLETE` | Every eligible file has current complete evidence, and every critical obligation is fulfilled. | After the runtime gate, run with current evidence. |
| `QUALIFIED` | Every critical obligation is fulfilled and all critical files are complete, but non-critical work is pending, stale, limited, external-boundary, or failed. | After the runtime gate, run and return global coverage limitations. |
| `INCOMPLETE` | A critical obligation is unmatched, a critical file lacks current complete evidence, or an ignore-critical conflict is active. | After the runtime gate, reject operations for that evidence family. |
| `UNAVAILABLE` | No admissible persisted generation can be served, including while the project model is unavailable or that evidence family is explicitly disabled. | After the runtime gate, reject operations for that evidence family. |

State precedence is `UNAVAILABLE`, `INCOMPLETE`, `COMPLETE`, then `QUALIFIED`.
After availability is established, active ignore-critical conflicts, unmatched
critical obligations, and incomplete critical files are evaluated before the
empty or fully complete inventory cases. An empty inventory is complete only
after every declared module has a complete product-specific model capability.

All operations that read persisted semantic graph evidence use the global graph
state. All operations that read persisted reference evidence or
reference-derived topology use the global reference state. Reference-derived
topology remains reference evidence even when a result presents it beside
graph facts. It does not gate semantic graph construction or graph coverage.
The design does not compute path-specific or operation-specific readiness.

Focused live IDEA reference search remains a runtime semantic operation. It can
use the existing live PSI fallback when persisted reference evidence is
unavailable or empty, and it retains its per-request relationship coverage. It
does not write persisted evidence and does not derive a new global reference
state. Runtime readiness, not persisted reference coverage, admits that live
operation.

A graph result admits its semantic graph section from the global graph state.
It includes reference-derived topology only when the global reference state is
`COMPLETE` or `QUALIFIED`. If reference coverage is `INCOMPLETE` or
`UNAVAILABLE`, the result omits that topology, attaches the corresponding
reference error and counts, and retains the graph operation exit status.

A qualified positive result is usable. A qualified empty result is not proof
that no matching fact exists. Results must retain the generation, global state,
counts, and limitation codes that explain that boundary.

Availability depends on committed inventory and stage outcomes, not on a
non-empty fact table. A compatible store without a committed current inventory
is `UNAVAILABLE`. After inventory commit, zero eligible files is `COMPLETE` only
when every declared module has a complete product-specific model capability and
there is no unmatched critical obligation. An accepted obligation that lost its
last match keeps the state `INCOMPLETE`.
With eligible files and no critical obligations, non-critical pending work is
`QUALIFIED`. A complete stage can emit zero facts and still counts as complete.

`COMPLETE` and `QUALIFIED` persisted-evidence operations exit successfully.
`INCOMPLETE` and `UNAVAILABLE` graph operations retain
`GRAPH_EVIDENCE_INCOMPLETE` and `GRAPH_EVIDENCE_UNAVAILABLE`. Persisted
reference operations use the corresponding `REFERENCE_EVIDENCE_INCOMPLETE` and
`REFERENCE_EVIDENCE_UNAVAILABLE` typed errors. These errors exit non-zero.
Errors and qualified results include total, complete, critical-file,
critical-obligation, unmatched-critical-obligation, pending, stale, limited,
ignore-critical-conflict, external-boundary, and failed counts. No operation
computes a different global readiness state from its requested symbol or path.

Graph and reference states can differ. For example, graph coverage can be
`COMPLETE` while reference coverage is `QUALIFIED`.

## Persistence and consistency

The sidecar preserves the existing exact-root SQLite database and generation
contract. It scans outside transactions and serializes short writes. Readers
pin one generation and reject mixed-generation results.

The project-model semantic fingerprint contributes to both stage input
fingerprints, together with source content, admitted membership, and source
ownership. The model fingerprint represents the source's Gradle dependency
closure, compiler arguments, content-addressed classpath or ABI entries,
language settings, and source-set identity. A completed model refresh compares
those per-source fingerprints and, in one transaction, marks affected graph and
reference outcomes pending before it advances the model generation. Unchanged
modules retain their facts. If a hard-excluded dependency has no complete
classpath or ABI fingerprint, affected evidence remains `UNAVAILABLE`; the
sidecar does not ingest that path as source.

Each stage input also contains a semantic cohort fingerprint: a normalized
Merkle root of compiler-input source content hashes for the source's compilation
unit and its transitive Gradle source-dependency closure. It includes model-owned
user-ignored sources that can contribute declarations, while graph and reference
outcomes remain restricted to admitted sources. A peer declaration change
therefore invalidates unchanged admitted sources in the same compilation unit
and every downstream compilation unit whose closure changed. One short
transaction marks both stage outcomes pending before the filesystem event epoch
and store generation advance. The shared fingerprint does not make either
pipeline wait for the other pipeline's outcome.

A change to `.kastignore`, ignored paths, hard exclusions, or source ownership
invalidates only affected stage work. A critical-path change updates priority
and coverage metadata without invalidating current facts.

The exact-root writer lease centralizes cross-mode arbitration. Managed and
standalone writers cannot overlap, while read-only CLI and IDEA consumers can
continue to use SQLite generation checks as the incumbent commits later
batches.

The shared `index-store` coordination authority owns the writer-lock protocol
and `<coordination-root>/writer.lock`. A managed or standalone sidecar acquires
the operating-system lock and holds its file descriptor before it opens the
store for writes. IDEA opens persisted evidence read-only in sidecar mode.
Startup fails the contender with a typed writer-conflict error if another
process holds the lock.

The release cutover first asks the old IDEA index worker to stop and drain. The
resident service requires confirmation that the worker closed its writable
store; an older writer is not assumed to honor the new lock. Without
confirmation, the sidecar does not start or migrate and the service reports
`INDEX_WRITER_CUTOVER_FAILED`. After confirmation, the sidecar can acquire the
lock and open the store. The operating system releases the lock when the
sidecar exits, so a matching service can recover after a crash without guessing
writer liveness.

One scope-reconciliation transaction removes excluded graph and reference
facts, records the new scope fingerprint, acceptance-ledger generation, and
coverage metadata, and advances the store generation. New admissions remain
pending after that commit. A reader therefore cannot observe removed facts with
the new coverage claim.

## macOS distribution and lifecycle

The macOS setup bundle includes a release-matched headless component and an
architecture-matched Java 21 runtime. The component uses its own IntelliJ
libraries, plugin files, and Java runtime. It does not reuse the interactive
IDEA application files and does not download a runtime on first use.

The headless component also contains the release-matched Android/AGP model
provider and its dependencies. The payload manifest declares its model-provider
protocol and host-product compatibility. Exact-root bootstrap validates that
capability before it launches an Android Studio-owned root. Managed and
standalone roles use the same provider; neither imports model state from the
interactive host.

The launcher assigns workspace-keyed `idea.config.path`, `idea.system.path`,
log, and temporary directories below machine-local Kast state. The key is a
stable digest of the canonical exact root and sidecar release identity. Two
concurrent roots therefore do not share IntelliJ caches or locks. One exact
root still owns only one sidecar.

The sidecar uses only its bundled Java runtime. Its launcher resolves `java`
inside the installed sidecar and fails with a typed installation error when
that runtime is absent or has the wrong architecture. It does not fall back to
`JAVA_HOME`, `java` from `PATH`, or the interactive IDE Java runtime.

The release pipeline currently places one Linux-built headless backend archive
in every setup bundle. That archive contains platform-native libraries and no
JVM. This design builds separate macOS arm64 and x64 headless archives on
matching macOS runners, combines each with its matching Java runtime, and
changes the public install and runtime routes to use the complete payload.
Setup verification must prove:

- the bundled sidecar matches the installed Kast release;
- the headless archive and native libraries match the setup-bundle platform;
- the bundled Java runtime matches the setup-bundle architecture;
- the bundled Android/AGP provider matches the sidecar release and protocol;
- the sidecar starts with `JAVA_HOME` unset and no system `java` on `PATH`;
- the sidecar starts on macOS for one exact workspace root;
- IDEA and the sidecar have distinct process and virtual file system state;
- only the sidecar opens the index for writes;
- stopping the exact-root runtime drains the sidecar before shared state closes.

The release produces and records separate macOS arm64 and x64 headless backend
artifacts before it assembles the setup bundles. Release checksum and provenance
checks cover each native backend, Android/AGP provider and dependency, matching
Java runtime, and final setup bundle. Release review must also confirm the
IntelliJ, Android/AGP provider, provider-dependency, and Java runtime
redistribution terms and report the setup-bundle and resident-memory increase.

The Rust bundle manifest records the native sidecar, its model-provider
capabilities, and Java runtime with their checksums, architecture, release
identity, and install paths. Rust packaging and install operations validate and
activate the payload in one transaction, write every component to the
installation receipt, and roll the payload back on failure. `install.sh`
remains a delegate to that authority.

General runtime readiness remains independent of sidecar indexing progress.
Status output reports runtime readiness, graph coverage, and reference coverage
as separate fields.

## Store preparation

After the old writer drains and the sidecar acquires the writer lock and a
role-tagged provisional lease, one bootstrap worker enters `PREPARING`. This
worker is the only process that can create, migrate, replace, or open the
exact-root store for writes. The CLI and resident project service can launch the
worker and report its result, but they never open the database for writes. The
worker does not publish an admission endpoint or promote its lease while it is
preparing the store.

The Rust-owned startup snapshot includes the resolved
`indexing.remote.enabled` and `sourceIndexUrl` values and any credential handle
needed by the existing remote-index transport. It contains no credential secret
or mutable configuration source. The bootstrap worker applies this fixed
precedence before it constructs the active store:

1. use the active store recovered from a valid committed preparation record;
2. otherwise use a valid self-contained canonical local database, migrating a
   current-production candidate when required;
3. otherwise prepare a valid legacy repository base and overlay as one local
   candidate;
4. otherwise, when configured, attempt remote snapshot hydration;
5. otherwise create an empty target-schema candidate for normal local indexing.

Before this precedence, the bootstrap worker replays the crash-safe
store-preparation ledger under the writer lease. The ledger assigns monotonic
preparation identities and retains both `PREPARED` and `COMMITTED` provenance.
The active-store locator is an atomic projection of the highest valid
`COMMITTED` record, and both must agree before admission. A durable `PREPARED`
record with a renamed candidate but no committed locator resumes the same
compare-and-set commit instead of selecting a retained canonical database. A
candidate that has not been renamed is revalidated and resumed or rebuilt from
its unchanged source. Once a preparation record or non-empty versioned store
directory exists, a missing, corrupt, or inconsistent locator must be recovered
from the ledger or fail with a typed store-recovery error. It never falls
through to a retained canonical database, overlay, remote seed, or empty store.
After a versioned-store activation commits, its superseded canonical or overlay
family is recovery provenance only and is never an admission target. A
target-schema canonical database adopted in place is not superseded; its
committed adoption record and locator make that same path the active store.

Local state always wins over a remote seed. A valid canonical target-schema
database is validated and adopted in place. Under the same guard and fence
checks as staged activation, the worker records `COMMITTED` adoption provenance
and publishes an active-store locator that names the existing normalized path
and store identity. Adoption performs no copy, rename, or migration, but it must
commit the locator before reconciliation or admission. A crash before locator
commit replays the adoption record rather than treating the database as a new
source candidate.

After old-writer drain and writer-lease acquisition, a canonical
current-production database is snapshotted to same-filesystem staging through
SQLite's Online Backup API from a production-schema connection. The source
identity, schema, repository identity, and generation are read from that same
committed snapshot. This includes committed frames visible in the source WAL
but not checkpointed into its main file, and excludes uncommitted work. Raw
main-file, WAL, or shared-memory copying and source checkpoint mutation are
prohibited. The worker closes and independently reopens the staged database,
verifies its identity, generation, and integrity without the source sidecars,
and only then migrates it. Backup, validation, or migration failure leaves the
source logical database and generation unchanged.

The presence of a committed active-store locator, canonical database, or
repository-overlay manifest claims the local-state branch. Invalid or corrupt
local state returns a typed diagnostic and does not fall through to remote
hydration or empty creation.

A legacy overlay preflight uses a reader compatible with its production schema
to validate the manifest, base and overlay identities, schema versions,
repository identity, and recorded digests. One consistent SQLite read snapshot
materializes their effective logical view into one same-filesystem staging
database: overlay rows replace matching base rows and tombstones remove base
rows. It includes committed overlay WAL state without copying source sidecars.
The candidate retains the resulting inventory, stage outcomes, facts, and
provenance as effective lineage. The worker never attaches the old immutable
base to a target-schema connection and never mutates the base or overlay. It
migrates the self-contained candidate instead.

A readable legacy pair with a valid but unsupported schema uses a staged cold
rebuild and makes no retained-fact claim. A missing, mismatched, or corrupt base
or overlay is not an unsupported-schema rebuild: preparation returns a typed
legacy-store diagnostic and leaves the active pair and generation unchanged.
No partially materialized candidate can replace it.

When no local state exists and remote indexing is configured, the sidecar
downloads the snapshot into same-filesystem staging before it creates a
canonical database. It verifies the existing hydration contract, including the
checksum, provenance, repository and snapshot identities, and schema. A
target-schema snapshot becomes a candidate directly; a current-production
snapshot uses the online migration below. An unavailable, corrupt, mismatched,
or unsupported snapshot, or a failed migration of that remote candidate,
records a typed hydration diagnostic, then creates an empty staged candidate
and continues normal local indexing. This is a failed best-effort seed, not
hydration success, and it cannot produce a coverage claim.

Every staged candidate carries a preparation identity and contains one closed,
self-contained SQLite database. A crash-safe receipt records `PREPARED` after
the candidate and its provenance are durable. Before activation, the worker
acquires the same shared host-claim commit guard used for batch commits,
revalidates the writer lease, host claim, repository identity and revision,
resolved configuration, critical-path ledger, project-model fingerprint, and
filesystem event fence under that guard, and holds it through activation,
directory fsync, and the committed receipt. Claim publication requires the
incompatible exclusive guard. A changed input detected before activation aborts
the candidate without changing the active generation.

Activation renames the candidate directory to a never-used
`<coordination-root>/stores/<preparation-id>/` path, records and fsyncs
`COMMITTED` in the preparation ledger, then atomically publishes the matching
active-store locator. The record and locator name the physical database path and
active-store identity later published in the admission record.
Activation never replaces a main database file at a pathname where an old hot
journal, WAL, or shared-memory file can remain. It never copies source sidecars,
changes the original canonical or overlay family, or deletes that family during
cutover. A retry before activation rebuilds from the unchanged source. A retry
after the directory rename but before the committed record validates the
preparation identity and finishes the compare-and-set ledger update. Replaying
the same snapshot is idempotent, and an older sequence or snapshot never
replaces a newer committed identity.

Preparation alone does not admit evidence. After selecting an existing target
or activating a staged candidate, the worker opens the target-schema store and
reconciles the current project model, inventory, scope, configuration and
critical-path ledger, stage fingerprints, and filesystem fence. Imported
completeness is lineage, not current local coverage. Only after reconciliation
commits can the worker publish admission and promote the provisional lease.

## Migration

The implementation adds an explicit online migration from the current
production schema to the sidecar schema. It runs only on a self-contained
staged candidate prepared from a canonical database, a materialized legacy
overlay, or a hydrated remote snapshot. A target-schema candidate bypasses it.
One transaction adds the required scope and coverage metadata and preserves
admitted inventory and legacy fact and outcome rows as non-queryable lineage. A
canonical candidate retains its raw rows; a materialized overlay retains only
its effective logical rows. Legacy model provenance is absent from the current
schema, so the migration does not assign a new model fingerprint to those rows.
It marks graph and reference work pending for every admitted source, applies the
legacy-boundary transformation below, advances the generation, and commits
before the sidecar serves queries. Reindexing under the completed current model
makes new outcomes queryable. A failed supported local or overlay migration
rolls back, preserves the old local authority, and reports coverage as
`UNAVAILABLE`; it does not fall through to destructive rebuild. A failed
supported migration of a remote seed follows the typed empty-seed fallback in
Store preparation because no local authority exists.

Any other valid, readable but unsupported schema version follows the existing
cold rebuild path. Corruption and missing or mismatched overlay components use
the preparation failure above instead. The cold path makes graph and reference
coverage `UNAVAILABLE`, recreates the schema in staging, commits a new
inventory, and then computes coverage normally. It makes no retained-fact
claim. The Rust-owned critical-path acceptance ledger remains beside the
workspace TOML and is reapplied to the new inventory; the rebuilt store does not
infer or erase committed acceptance. Pending receipts are reproved, not
reapplied. Migration does not infer complete coverage from old module summaries.

For a current relationship `EXTERNAL_BOUNDARY`, the online migration preserves
the relationship outcome and reason identity as non-queryable lineage and
queues relationship re-evaluation. It removes the legacy semantic-graph boundary
that relationship externalization projected as `UNKNOWN`, marks semantic graph
work pending for the new complete stage input fingerprint, and queues graph
extraction. The legacy projection does not become graph evidence or graph
coverage. A re-evaluated external boundary follows the normal current-model
coverage contract.

The initial scope reconciliation removes hard-excluded inventory and removes
graph and reference facts for user-ignored files. It retains current admitted
lineage, including relationship external-boundary outcomes and their reason
identity, and queues both stages. Only outcomes produced under the complete
current stage input fingerprint become queryable. Existing generation checks
remain active during this transition.

macOS keeps the exact-root IDEA or Android Studio backend as the automatic
interactive default. An explicit standalone start is permitted on macOS when
`backends.headless.enabled` is `true`. It uses the installed
architecture-matched native payload and bundled Java runtime, publishes its
normal standalone descriptor and control endpoint, and must acquire the
exact-root writer lease. It is never selected automatically. When the setting
is `false`, an explicit start returns `HEADLESS_BACKEND_DISABLED`. Public
mutation commands do not implicitly select an unleased headless backend. The
implementation removes the existing `HEADLESS_LOCAL_UNSUPPORTED` guard from
this explicit standalone route on supported macOS hosts; that error cannot mask
the enabled or disabled outcomes above.

## Verification plan

Implementation is complete only when the following checks exist and pass:

1. Configuration tests cover the Rust resolved JSON snapshot, `.kastignore`,
   collection mutations, global and workspace precedence, external-edit live
   reload and admission fencing, Rust-issued critical-path acceptance receipts
   across a cold rebuild, pending-receipt recovery, stale candidate-proof
   rejection, add and removal crashes, tombstone replay, relationship disable
   and re-enable, anchored and unanchored module priority depth, semantic
   output-root classification, hard-exclusion precedence over nested generated
   source roots, independent builds named `build`, and pre-ignore conflict
   detection with hard-exclusion precedence. They also prove one external-edit
   producer per writer lease, direct-mutation and watcher-trigger serialization
   through the full critical-path receipt protocol, and stale lease-epoch
   rejection. Android configuration tests cover explicit variant selection,
   the exact `debug` and sole-variant defaults, ambiguous and unknown variants,
   composite-build project-path collisions, and live model invalidation after a
   selection change. Configuration-root tests start the CLI with an override
   visible only to that process while a reused IDEA host has no override or a
   conflicting one. They prove managed, restarted-managed, standalone, and
   standalone-first adoption producers watch only the bound file. A complete
   IDEA process restart and automatic project reopen reuse the prior explicit
   binding with no CLI environment. Direct first host open uses the installed
   Rust default only when no binding exists. An identical bootstrap preserves
   the binding epoch. A different root during a live writer lease returns the
   typed conflict, stale binding epochs and path mismatches fence admission, and
   an interrupted binding update leaves one complete old or new record without
   two producers. Simultaneous no-incumbent bootstraps with different roots prove
   the provisional-lease winner captures its own expected binding and the loser
   starts no watcher.
2. Index-store tests cover independent graph and reference progress, the
   fact-preserving current-schema migration, unsupported-schema cold rebuild,
   legacy rows remaining non-queryable until reindex, external-boundary graph
   cleanup, model-only dependency, compiler argument, and classpath changes,
   admitted and user-ignored peer declaration changes invalidating unchanged
   callers in the same and downstream compilation units, scope reconciliation,
   global coverage states, and retained generations. Android model tests include
   model-provider identity, AGP version, selected variant, source-provider
   ownership, and compiler-cohort identity in the model and stage fingerprints.
3. A RED worker integration test injects request cancellation and proves the
   current request-owned path stops remaining graph work. The same test then
   proves the workspace-owned worker continues. This proves isolation without
   claiming reproduction of the reported production contention.
4. Worker tests prove cancellation remains pending, three repeated failures for
   one complete stage input become a retryable limited outcome, a model or peer
   change starts a new failure sequence, current external-boundary evidence
   keeps its reason identity, and stale prior facts stay unqueryable.
5. Runtime tests under each supported macOS host—IntelliJ IDEA 2026.2/build 262
   and Android Studio 2026.1.2/build 261—prove startup owns one exact-root
   sidecar and one persistent writer even when public headless selection is
   disabled. Refresh forwarding, scope reload, crash, and cutover cases must
   prove acknowledgment, lock release, restart, drain, and generation
   preservation. Runtime-and-coverage tests prove not-`READY` with retained
   `COMPLETE` or `QUALIFIED` evidence returns the existing runtime error;
   `READY` with `INCOMPLETE` or `UNAVAILABLE` returns the evidence-family error;
   and `READY` with `COMPLETE` or `QUALIFIED` succeeds. Status reports all three
   facts without creating command-specific readiness states.
   Focused live reference tests retain PSI fallback without
   persisted-reference admission. Discovery tests prove the internal sidecar
   publishes no runtime descriptor and automatic backend selection still
   selects only the exact-root host runtime. Selection tests prove public
   headless disablement returns `HEADLESS_BACKEND_DISABLED` without blocking the
   internal role. Explicit-start tests prove an explicit standalone start is
   permitted on macOS only when the setting is enabled and the writer lease is
   free, never returns `HEADLESS_LOCAL_UNSUPPORTED` on a supported host, uses
   the bundled runtime, and returns the typed incumbent conflict when a managed
   writer owns the lease. Lifecycle tests prove a disabled existing standalone
   runtime remains inspectable and stoppable, fences persisted admission, and
   rejects semantic work until explicit stop or confirmed process death.
   Writer-arbitration tests cover managed-first and standalone-first startup,
   typed incumbent errors, explicit standalone drain and stop, and lock handoff
   without overlapping writers. A simultaneous-start test proves provisional
   lease publication exposes the winning role and identity before either
   endpoint exists. Standalone-first adoption tests prove the resident service
   validates matching descriptor, admission-record, and writer-lease identities,
   forwards refresh and configuration notifications, and leaves the standalone
   process running when the `CLIENT` channel closes. They prove an incompatible
   incumbent returns the typed control-protocol error and remains fenced until
   explicit stop. A no-resident test proves a raw configuration event invokes
   the release-matched Rust projection helper, including the complete
   critical-path receipt exchange, and reconciles the resulting snapshot. A
   standalone-crash test proves endpoint loss plus lock release invokes stale
   cleanup and managed handoff without restarting the standalone role.
   Standalone tests cover zero-to-one, one-to-zero, and single-host-replacement
   generation rebinding. Dual-host tests cover
   simultaneous claims and a second host arriving after managed indexing starts.
   They require `IDEA_HOST_AMBIGUOUS`, immediate admission refusal,
   supervisor-lease revocation, sidecar exit, and writer-lock release before
   either host can restart managed indexing. A commit-race test injects the
   second claim between generation validation and SQLite commit and proves the
   claim or batch completes first under the coordination guard, never both
   concurrently.
   Persisted-reader tests open the SQLite transaction at `g`, commit `g+1` under
   the same read fence, and return facts, state, counts, and limitations from
   `g`. Before target-protocol cutover, the same advance still rejects. After
   cutover, a cursor issued at `g` cannot open a new page at `g+1`, and a refresh
   or write with `expectedGeneration = g` cannot commit at `g+1`. Tests reject a
   missing endpoint, a lower generation, or any changed store, instance, lease,
   scope, model, event, host-claim, configuration, or acceptance-ledger
   identity. They also cover long-path fallback, physical-store path and record
   identity validation, and reject a record whose store identity or normalized
   path differs from the committed locator or admission fence. They cover
   graceful unlink and stale record and socket cleanup.
   Watcher tests prove the
   pre-read filesystem barrier invalidates a completed fingerprint, a delayed
   producer delivery is consumed before token issue, dropped history falls back
   to a complete snapshot comparison, a concurrent save fails post-read
   revalidation, and raw configuration saves block admission until the matching
   Rust snapshot and scope reconciliation commit.
   Supervisor-crash tests prove control-channel EOF or lease expiry stops the
   orphan, releases its lock, and permits replacement.
   The Android Studio case imports a real AGP application-and-library fixture
   with two flavor dimensions and `:app` configured for a non-default
   `demoFreeDebug` variant. It proves the isolated provider owns
   `src/main/kotlin`, each individual flavor root, the combined-flavor root
   `src/demoFree/kotlin`, and `src/demoFreeDebug/kotlin`. It excludes generated
   output and commits generation-pinned graph edges and references across the
   main, combined-flavor, and full-variant roots. A variant or AGP-model change
   invalidates that evidence.
   Missing or incompatible provider capability keeps both evidence families
   `UNAVAILABLE`; generic Gradle discovery and zero-file `COMPLETE` are rejected.
6. Packaging tests prove native macOS arm64 and x64 headless artifacts contain
   matching native libraries and that the normal install path installs the
   matching Java 21 runtime. A launch test unsets `JAVA_HOME`, removes system
   `java` from `PATH`, and proves the installed sidecar selects its bundled
   runtime. Both architecture payloads contain the release-matched Android/AGP
   provider and capability manifest. A missing or mismatched provider produces
   the typed model-capability failure before indexing. Transaction tests prove
   the bundle manifest, installation receipt, and rollback cover the native
   sidecar, model provider, and Java runtime.
7. Under each supported host, a macOS integration check edits a saved Kotlin
   file while the host is active, attempts reads both before and during sidecar
   reconciliation, and proves no stale generation is admitted without using the
   host virtual file system for persistence. It also saves a model-affecting
   input and proves both evidence families become unavailable before admission
   and remain unavailable until a completed model refresh is reconciled. Cases
   cover external included-build inputs, custom Gradle configuration inputs, and
   incomplete provenance.
8. A macOS integration check opens two exact-root projects concurrently and
   proves their sidecars use distinct IntelliJ config, system, log, and
   temporary directories.
9. Store-preparation tests prove first adoption of a valid canonical
   target-schema database bypasses legacy and remote paths, records a committed
   locator for its existing normalized path without copying or renaming it, and
   admits only that store identity. Local overlay state always wins over a
   configured remote seed. Invalid or corrupt canonical state fails without
   remote or empty fallback. A canonical current-production WAL fixture disables
   auto-checkpoint, holds a reader open, and commits inventory, outcome, fact,
   and generation rows that remain only in the WAL. Online backup and migration
   retain those rows at the exact committed generation, exclude an uncommitted
   row, and reopen without the source WAL or shared-memory file. Interrupted
   backup or migration leaves the original logical state and generation
   recoverable and unchanged. A
   production-schema fixture with base rows `{A, B, D}`, overlay rows `{B', C}`,
   and tombstone `{D}` materializes effective lineage `{A, B', C}` without
   mutating its base or attaching that base after the schema bump. Missing,
   mismatched, or corrupt overlay components leave the old pair and generation
   unchanged; a valid unsupported schema uses a staged cold rebuild. Remote
   tests prove hydration is attempted before canonical creation, target and
   current-production schemas follow their specified paths, and rejection,
   transport failure, or remote-candidate migration failure records a typed
   diagnostic before empty local indexing. Crash injection after download,
   materialization, backup, migration, versioned-path rename, and locator writes
   proves deterministic recovery without mixed database families. A crash after
   the versioned-path rename with `PREPARED` durable, the locator absent, and the
   old canonical database still valid resumes the same commit and never selects
   that old database. Missing or corrupt locator tests recover from the ledger
   or fail typed without legacy fallback. The coordination root and writer lock
   path remain identical before and after the active physical path changes. A
   host-claim race cannot publish during activation. Concurrent managed and
   standalone starts produce one preparer, and no path publishes admission
   before current-input reconciliation.
10. Operation-level hard-exclusion tests register
    `:app/build/generated/ksp/main/kotlin/Generated.kt` as model source content
    below the declared project output root. Inventory, diagnostics, source-taking
    symbol operations, graph and reference refresh and query selection, and
    mutation preview and apply all return the same typed hard-excluded decision.
    Critical configuration, ignore negation, and a symbolic-link alias cannot
    admit it. Rejection leaves file bytes, store generation, and both evidence
    families unchanged. A forged or stale serialized admission is rejected by
    the receiving authority. A plan-and-apply test pauses after admission
    validation while exclusion publication races for the exclusive guard. It
    proves exactly one order: publication first rejects without a write;
    mutation first commits before the new exclusion becomes authoritative. A
    read-side race pauses after admission, retargets the symbolic link to the
    excluded output, and replaces the prior canonical file. Each PSI and
    compiler path either reads the original identity-bound target or rejects its
    post-read identity or event check; it never returns evidence for the
    excluded target. A positive operation case retains `build/src/main/kotlin`
    for an independent Gradle build when the parent model does not classify that
    root as output, while that build's own `build/build/` output remains
    excluded.

## Implementation ownership

| Contract | Owning area |
| --- | --- |
| Exact-root bootstrap, host-product/payload capability binding, and global runtime `READY` admission gate | `cli-rs/src/execution/runtime/` |
| Resident host-product claim, supervision, refresh forwarding, and writer cutover; no model or VFS sharing | `backend-idea/` |
| Sidecar control, independent runtime and coverage status, read-fence schema, and typed result contracts | `analysis-api/`, `backend-headless/`, and `cli-rs/src/semantics/` |
| Pre-read admission barrier and post-read same-fence comparison | `backend-headless/` |
| Read-generation supersession, protocol cutover, legacy equality compatibility, and cursor and write-CAS exact-generation checks | `analysis-api/`, `backend-headless/`, `index-store/`, and `cli-rs/src/agent/navigation/native_graph/` |
| Saved-file JVM and Android/AGP model, selected variants, source-provider ownership, and background worker | `backend-headless/` |
| Focused live analysis and read-only persisted access | `backend-idea/` |
| Immutable exact-root coordination directory, host-claim CAS, writer lease, product-model fingerprints, single-generation facts and coverage snapshots, stage state, scope reconciliation, and generations | `index-store/` |
| Workspace collections, `.kastignore`, Android selected variants, precedence, resolved JSON projection, and configuration transaction serialization | `cli-rs/src/configuration/` |
| Resolved configuration and control-message schema | `analysis-api/` |
| Managed and standalone external-edit watchers and snapshot transport | `backend-idea/` and `backend-headless/` |
| Bootstrap-resolved configuration-root binding, durable epoch, startup and control transport, producer selection, and admission fencing | `cli-rs/src/execution/runtime/`, `cli-rs/src/configuration/`, `analysis-api/`, `backend-idea/`, `backend-headless/`, and `index-store/` |
| Typed `AdmittedSourcePath` and sole hard-exclusion classifier | `analysis-api/` and `backend-shared/` |
| Hard-exclusion operation wiring, serialized Rust consumption, and persisted scope projection | `backend-idea/`, `backend-headless/`, `cli-rs/`, and `index-store/` |
| Critical-path receipt and ledger authority | `cli-rs/src/configuration/` |
| Critical-path inventory proof and prepared scope-generation commit | `backend-headless/` and `index-store/` |
| Resolved remote-seed input and hydration-result schema | `cli-rs/src/configuration/` and `analysis-api/` |
| Pre-open preparation coordinator and remote transport | `backend-headless/` |
| SQLite-consistent backup, schema preflight, effective overlay materialization, migration, preparation receipts, active-store locator, atomic activation, and recovery | `index-store/` |
| Native macOS component, Android/AGP provider, and Java runtime manifest, packaging, installation, receipt, and rollback | `backend-headless/build.gradle.kts`, `cli-rs/src/configuration/bundle.rs`, `cli-rs/src/operations/package.rs`, `cli-rs/src/operations/install/`, `install.sh`, `.github/workflows/release.yml`, and `.github/scripts/release/actions/build-setup-bundle/` |

## Consequences

- Interactive IDEA virtual file system pressure cannot directly cancel durable
  index ownership.
- Non-critical failures no longer make all graph or reference evidence
  unavailable.
- Projects can opt into strict global coverage without changing default
  usability.
- The worker consumes memory for a second IntelliJ JVM on macOS.
- Saved disk state, not unsaved editor state, becomes the persistent evidence
  authority.
- Operators must inspect three independent states instead of treating runtime
  readiness as graph readiness.

## Rejected alternatives

- **Run a background job inside IDEA.** It still shares the interactive virtual
  file system and cancellation environment.
- **Let both backends write.** This adds writer election and recovery without a
  second required writer.
- **Keep graph refresh request-owned.** A cancelled client can still stop
  durable progress.
- **Make graph wait for references.** The graph extractor does not consume the
  persisted relationship stage.
- **Compute readiness per operation.** One global state per evidence family is
  sufficient until a measured use case requires finer scope.
- **Persist unsaved document overlays.** This would mix interactive and durable
  source authority.
- **Reuse system Java or the interactive IDE runtime.** This would make sidecar
  startup depend on workstation or host-application state.
- **Download the sidecar on demand.** Startup would depend on network state and
  could install a release-mismatched backend.

## Related current-state references

- [IDEA indexing and generation](flows/indexing-and-generation.md)
- [Native graph refresh and queries](flows/graph-queries.md)
- [IDEA integration architecture decisions](architecture-decisions.md)
- [IDEA shutdown](flows/shutdown.md)
