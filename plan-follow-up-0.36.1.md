# Plan: version-owned installations and stable multi-workspace execution

Status: proposed implementation plan, not a verification receipt.

Baseline: `amichne/kast` main at `660efa785f4c8498def2ce13409cbc632315cb1f`, the `v0.36.1` source revision, rechecked on 2026-09-08. Preserve the released contained-link and configurable-sidecar-heap behavior. This document replaces the previous follow-up scope.

## 1. Goal and evidence

Make one installed Kast instance accept multiple App Server connections, bind each thread to the correct registered workspace, and supervise independent workspace runtimes without allowing a heavy Gradle import, stalled client, or stale process to compromise other workspaces.

Give every Kast-owned artifact a versioned installation owner. Make configuration discoverable through one typed catalogue, and make stopping, resetting, upgrading, and removing an installation explicit ownership transitions rather than a search through scattered directories.

The reported problems are stalled interaction despite passive readiness, incomplete workspace/session binding, and ambiguous surviving broker state. They motivate this plan; they do not establish the precise wait or resource bottleneck on the private machine. No new live-workload reproduction was performed for this document.

Current source establishes the following narrower facts:

| Boundary | Inspected behavior | Required change |
| --- | --- | --- |
| Installation | Control payloads use `versions`/`current`, while defaults place configuration, runtime payloads, caches, and sockets in separate locations. | Extend version ownership to mutable state and configuration, not just executable selection. [install] |
| Broker | Broker files live under `CODEX_HOME/broker`; the public socket and service label are derived from Codex home. | Derive Kast service identity and endpoints from its installation, not another application's home. [broker] |
| Workspace routing | Enrollment holds one root; the protocol adapter uses that enrollment for thread admission and invocation routing. | A live registry and immutable thread-to-workspace bindings. [enrollment] [routing] |
| Multiplexing | `BrokerSessionHub` already has per-client sessions, handshake deadlines, and bounded channels, but one `workspaceExecution` mutex. | Preserve connection isolation; partition execution admission by workspace and keep global coordination short. [hub] |
| Liveness | CLI socket I/O has no elapsed deadline; indexer connection handling is serial and awaits dispatch without an elapsed bound at that boundary. | Bound admission, request I/O, and retirement without granting unsafe parallel semantic execution. [wire] [indexer] |
| Configuration | CLI composition reads settings directly; the broker maintains a separate child-environment list. That list omits `KAST_INDEXER_MAX_HEAP`, which CLI composition consumes. | One declared configuration contract and tested child projections. The omission is a source-level coverage gap, not proof of the heap used in the reported run. [composition] [broker] |

Do not replace existing process-identity checks with socket-exists or PID-file heuristics. The current host already distinguishes socket types, probes readiness, and has finite failure boundaries; preserve those protections while changing ownership. [host]

### Scope boundary

Deliver installation ownership, explicit configuration, multi-workspace routing, and bounded ingestion/indexing/interaction. Do not redesign semantic query algebra, compiler identity, history presentation, client UI, or mutation semantics. Do not add a generic scheduler, a separate delivery framework, or a second semantic backend. Keep existing replay fences and compiler authority.

## 2. Recommended architecture

Use one lightweight installation coordinator in the existing `:app-server` process. It owns service generation, connection admission, workspace registrations, resource reservations, and workspace-worker lifetimes. Each resident workspace retains its own isolated IntelliJ sidecar; do not combine several monoliths into one JVM.

An installation may have many registered workspaces and client connections without keeping all workspace runtimes resident. The coordinator multiplexes control traffic; bounded workspace workers execute semantic work. Reuse the existing upstream App Server/session mechanism, activated for the explicitly selected host profile. Do not create a broker or indexer for every frontend connection.

```text
version-pinned CLI / App Server frontends
                  |
        installation coordinator
        |         |          |
  connection A  connection B  connection C
        \         |          /
          immutable thread bindings
                  |
        workspace registry + admission
           /                     \
 workspace A worker         workspace B worker
 isolated sidecar           isolated sidecar
 model/cache/evidence       model/cache/evidence
```

The host profile owns upstream executable/home selection, not workspace selection. Multiple workspaces share the selected host profile without replacing its home or fragmenting its conversation state. A client cannot alter the installation's host profile through an unrelated workspace request.

Control readiness must not depend on indexing every registered workspace or completing an upstream sign-in. Report coordinator, host-adapter, and workspace readiness separately. Status, cancellation, connection admission, and unrelated ready-workspace requests remain serviceable during a long import.

Keep physical module ownership narrow:

| Owner | Responsibility |
| --- | --- |
| `:distribution:contract` | Shared installation/configuration identities, constrained values, pure layout and lifecycle contracts. |
| `:distribution:managed` | Filesystem realization, manifests, path ownership, and bounded deletion of admitted installation artifacts. |
| `:app-server` | Coordinator, host/session routing, live workspace registry, admission/resource policy, and existing process supervision. |
| `:cli` | User-facing configuration/status/lifecycle projections and existing installed sidecar launch boundary. |
| `:indexer` and existing workspace/runtime modules | Request lifetime, Gradle import, IntelliJ indexing, generation publication, and semantic execution. |
| Existing build logic | Configuration generation/parity, architecture checks, and focused verification. |

Add only the pure project dependency needed for shared installation/configuration values, with an explicit architecture-policy update. Do not let `:app-server` import CLI implementation, IntelliJ, or a general runtime handle. Semantic modules must not obtain process-control capabilities from the coordinator.

## 3. Version-owned state and lifecycle

### 3.1 Layout and identity

Retain `KAST_INSTALL_ROOT` as the outer installation selector. Resolve `current` once at process launch and retain the physical selected directory. Never follow it again while serving requests or spawning children.

The release directory identity includes the exact payload identity, not only a mutable semantic-version label. A reset creates a new state epoch; each process start creates a new service generation. Neither is a semantic source generation.

Proposed layout:

```text
$KAST_INSTALL_ROOT/
  current -> versions/<version-and-payload-id>/
  activation.lock
  versions/<version-and-payload-id>/
    installation.json
    bin/
    payload/
    runtime-payloads/
    config/
      installation.json
      workspaces/<workspace-id>.json
    state/
      epoch.json
      broker/<host-profile-id>/
      bindings/
      invocation-journals/
      workspaces/<workspace-id>/
        runtime/
        idea-system/
        idea-config/
        evidence/
        network/
        tmp/
      run/
      logs/
      recovery/
```

`config` contains desired configuration and workspace registrations. `state` contains applied configuration evidence, runtime ownership, live registry revisions, caches, bindings, journals, temporary files, and logs. Payloads remain immutable; mutable state does not participate in payload checksums.

No Kast-owned broker state belongs in `CODEX_HOME`. Repository contents, the user's Codex home and credentials, IDEA/JBR installation, and explicitly selected external Gradle homes remain external dependencies, not wipe targets. A Gradle home provisioned by Kast belongs under the versioned workspace state. Preserve the existing explicit environment/trust admission; do not copy entire external homes or silently change repository credentials.

### 3.2 The small, explicit exceptions

Complete ownership does not require pretending that OS integration files can all reside inside the release tree. Permit only declared external anchors:

- public command symlinks;
- the stable outer `current` selector and activation lock;
- an optional owned login-service entry;
- a short, private Unix-socket path alias when the versioned physical path exceeds the transport limit.

The current transport already accounts for short Unix-domain addresses. Prefer a short, owner-checked directory alias into version-owned `state/run`, so socket inodes remain under the versioned tree. Implement that as an admitted endpoint-alias type, not a blanket relaxation of canonical-path checks. Verify the exact alias route on supported macOS; reject an unrepresentable endpoint rather than silently returning to a global socket. [sockets] [host]

The installation manifest records every anchor, its exact owner/target, and its removal rule. The outer activation lock is a stable coordination inode, not a broker-discovery record. Do not unlink/recreate it while competing activation operations may hold it.

Use the Kast facade or explicit private endpoint for attachment. Do not seize the ordinary upstream daemon socket. Two test installations sharing an external host home must still have disjoint Kast endpoints and labels; upstream compatibility constraints remain separate.

### 3.3 Attach, stop, upgrade, and reset

A live attachment must prove installation identity, state epoch, service generation, protocol/catalog identity, and active configuration revision. A reachable socket or persisted readiness file is insufficient. Use bounded private handshakes and retain the admitted receipt across the connection boundary.

Separate incomplete connections from service ownership. Every accepted connection has a handshake deadline and owned resources. A frontend disconnect removes its subscription; it cannot leave a new broker owner or destroy the shared service. Work follows its explicit request/task lifetime, not the frontend process lifetime.

Represent service lifecycle as closed states such as `Starting`, `Serving`, `Draining`, `Stopped`, and `RecoveryRequired`. The existing service manager or direct-process adapter is the single admitted owner for that mode. Do not create simultaneous independent launchd and direct owners for the same instance.

Upgrade sequence:

1. Stage and verify the new immutable payload; parse explicitly carried-forward desired configuration and registration records.
2. Under the activation lock, stop admission to the old installation and drain owned work within declared bounds.
3. Prove retirement of its broker, sidecars, and owned registrations/endpoints. Failure blocks activation; no silent concurrent takeover.
4. Atomically switch `current`; start the new coordinator on demand with a new state epoch/generation and require clients to reattach.

This first delivery accepts a controlled interruption during upgrade. Zero-downtime cross-version handoff is not required. Never migrate live sockets, PID files, locks, readiness records, or authoritative running-thread bindings. Do not overwrite a running version in place.

Reset is a user-requested stop-and-recreate transition, not a retry policy. Proposed commands are `kast installation inspect`, `kast installation reset --dry-run`, and `kast installation reset`. Inspection lists the exact affected roots, external anchors, processes, retained configuration, and blockers without starting a runtime. Reset retires owned work, preserves desired configuration/payloads, and creates a fresh state epoch. Full version removal additionally deletes that version's configuration and payload after the same ownership checks.

Do not delete unresolved mutation journals as a recovery shortcut. Uncertain effects block ordinary reset until reconciled. Any separately authorized destructive abandonment must invalidate old bindings and disallow automatic replay. Never infer cancellation or source rollback from deletion of a process or directory.

Cleanup must be containment-safe, not follow arbitrary symlinks, and signal only identity-proven owned processes. Include process start identity in ownership checks; a recycled PID is not the original owner. Unproven foreign socket/service ownership produces a precise blocker, not a broad kill/unlink. An absent retired version must not be recreated by a surviving login registration or old child launcher.

After retirement, the release directory and its manifest-listed anchors are the complete removal set. Do not require users to hunt through caches, temporary directories, or another application's state to complete removal.

### 3.4 Migration cost

Provide a bounded, explicit migration from the inspected legacy layout. Enumerate existing paths, retire proven owners, validate and import desired settings/registrations, and record unresolved leftovers. No implicit legacy-directory fallback remains after activation.

Version-local caches trade disk space and potentially another Gradle import for independent removal and recovery. Do not hide that cost by sharing mutable cache trees. Reuse the existing admitted seed/copy mechanism only when compatibility and source quiescence are proven; fresh state remains valid. Cross-version mutable hardlinks and a new automatic cache-migration engine are out of scope.

## 4. One typed configuration contract

### 4.1 Declare once; project everywhere

Register every Kast-owned environment/system-property input, installer/CLI setting, delegated-environment policy, test override, and operational limit. This includes paths/homes, heap, executable discovery, trust/network settings, concurrency, frame/output limits, deadlines, and retention. Do not inventory third-party library internals as Kast settings.

Keep one definition per parameter. Module-owned declarations assemble into one deterministic catalogue; the owner remains authoritative. Existing operation budgets remain owned by `OperationExecutionBudget`, and the catalogue projects them rather than duplicating their defaults. Shared constrained values can remain in `:distribution:contract`. [budgets]

Each declaration defines:

| Field | Required meaning |
| --- | --- |
| Identity and owner | Stable key, documentation, owning module, supported input surfaces. |
| Value type | Parser, unit, default or explicit required state, admitted range/closed variants. |
| Scope | Installation, host profile, workspace, request, build, or test. |
| Provenance policy | Allowed sources and explicit precedence. |
| Propagation | Exact child consumers; no ambient forwarding by default. |
| Application boundary | Immediate pure request setting, next connection, next worker launch, or next installation activation. |
| Identity impact | Launch-only, routing, or model-affecting; no automatic inclusion in every cache key. |
| Disclosure | Public value, sensitive path, secret reference/presence, or redacted value. |
| Mutability | User setting, test-injected parameter, derived value, or fixed limit. |

Do not make every uppercase constant configurable. Fixed protocol limits and release identities are inspectable read-only declarations. Local implementation constants without configuration meaning remain local. A test parameter is not a hidden production override.

Use private constructors and closed outcomes for refinement:

```text
raw sources -> parsed assignments -> scoped resolved configuration
            -> admitted launch/request configuration -> applied snapshot
```

Runtime consumers receive typed owner-specific configuration records, not `Map<String, Any>`, repeated `System.getenv` calls, or raw strings plus validity flags. Register delegated Gradle variables as an explicit typed collection with name/value policy; never forward the entire caller environment.

### 4.2 Precedence and diagnostics

For a parameter that permits all these sources, precedence is explicit CLI input, explicit process environment, saved workspace override, saved installation value, then declared default. Unsupported scopes/surfaces reject rather than creating an accidental override. Explicit empty or invalid values do not fall back. Unknown Kast configuration keys and duplicate saved assignments reject; unrelated ambient environment is ignored.

Resolve installation selection before loading version-specific configuration. Legacy XDG/home configuration is an explicit migration input, not a second permanently active authority.

The coordinator retains a revisioned resolved configuration. Each worker records the admitted configuration it actually launched with, including observed heap. Editing a file or exporting a variable cannot alter a live broker/worker. Existing workers keep their applied revision until the declared restart/reimport transition occurs. Workspace model-affecting settings require fresh model evidence; heap remains launch-only.

Secrets are never included in reports, ordinary snapshots, logs, or plain value digests. Report source/reference and presence only. Keep secret resolution at the admitted effect boundary; do not use redaction to conceal which setting or provider was selected.

Proposed passive interface:

```sh
kast config schema --json
kast config show --json
kast config show --workspace /absolute/project --json
kast config explain KAST_INDEXER_MAX_HEAP
kast installation inspect --json
```

The schema lists every parameter, default, scope, owner, type, accepted surfaces, and application boundary. `show` distinguishes desired, resolved-next-launch, and currently applied values, plus provenance and pending changes. `explain` identifies the winning source, overridden sources, rejection reason, and restart requirement. Schema/inspection remain available when configuration is malformed or semantic startup is unavailable.

Bare `kast` prominently identifies the selected physical installation, effective homes, configuration revision, workspace status, and the configuration-inspection command. Status reads bounded records and live owner acknowledgements; it does not walk repository contents or start import/indexing.

### 4.3 Build and test enforcement

Generate schema, documentation, installer parsing/forwarding tables, and default projections from the declarations. The installer may need a generated pre-JVM parser; it must not maintain a handwritten second semantic contract. Cross-language fixture tests prove the same accepted and rejected values.

Extend existing architecture checks to reject undeclared environment/property reads outside approved ingress adapters, unknown settings, duplicate keys, missing consumer projections, and production consumption of test-only settings. Include installer and packaging scripts in coverage. Exemptions must name the upstream boundary and be reviewed, not be broad package suppressions.

Tests inject typed configuration and isolated homes/state roots. Retire independent temporary-directory overrides where installation-derived paths suffice. A test process must never fall back to the developer's real home after a bad test setting.

## 5. Multi-workspace routing and heavy-work admission

### 5.1 Bind the thread, not the connection

Replace singleton enrollment with a registry keyed by canonical workspace identity. Keep canonical root identity distinct from source/model generation. Different worktrees are different workspaces; path aliases canonicalize to one identity. A common parent containing several builds is not a substitute workspace.

Workspace registration is a coordinator operation with a finite acknowledgement and registry revision. Registration persists desired ownership but does not establish semantic readiness. Do not use desktop/login enablement to register every new workspace.

At thread creation, resolve the explicit workspace selection or effective `cwd` against registered roots. An explicit registered root must contain the working directory. For implicit selection, one matching root admits; no match requires explicit registration/selection; overlapping matches reject as ambiguous unless an explicit workspace resolves them. Do not invent an implicit fallback to `/`, the broker's launch directory, or the first registry entry.

One connection can carry threads in several workspaces; several connections can observe one thread. Persist a binding containing thread identity, workspace identity, installation/state epoch, and catalog identity. Acquire a live generation-bound runtime route for each invocation. Service generations are revalidated after restart, not treated as durable proof merely because they were serialized.

Route lookup uses the bound workspace, never the connection's last working directory. Queue entries, responses, cancellations, and completion records retain that workspace and request identity. Reused request IDs on separate connections cannot collide. Preserve existing controller/observer and native approval ownership.

Resume/reconnect must revalidate the persisted binding and current live route. Never silently retarget an existing thread or reconstruct a missing binding by choosing an available workspace. Source generation changes continue to invalidate semantic references through the existing runtime contract.

### 5.2 One runtime lifecycle per workspace

The coordinator owns resource reservation and the live workspace lifecycle; it invokes the existing installed launch boundary rather than reimplementing JVM bootstrap. Direct CLI and broker-originated requests use the same admission boundary. Internal worker commands consume an admitted reservation instead of recursively requesting another one. There must be no fallback launcher that bypasses resource admission when the coordinator is unavailable.

Represent lifecycle with closed states such as:

```text
Registered -> Queued -> Starting -> Importing -> Indexing -> Ready
                                                        -> Failed
Ready -> Refreshing -> Ready
Ready -> Draining -> Stopped
uncertain retirement -> RecoveryRequired
```

The implementation must encode valid edges, not permit arbitrary combinations of phase flags. Share the same startup operation among concurrent requests for one workspace; do not start multiple sidecars or Gradle imports. A waiting client's cancellation removes that waiter, not startup needed by other callers.

Retain isolated cache/model/evidence ownership per workspace and release. Connection attachment does not trigger reimport. Seeded caches do not themselves prove current model readiness. Publish a new semantic generation only after existing import/indexing authority establishes it; never serve stale proof as current while refreshing.

### 5.3 Bounded resources without global serialization

Partition execution queues and permits by workspace. Global locks cover only bounded registry, reservation, and ownership transitions. Do not hold them across Gradle, filesystem capture, socket waits, semantic dispatch, or a client's outbound write.

Declare bounds for resident workspace workers, concurrent heavy startup/refresh operations, per-workspace pending requests, connections, queued bytes, handshake time, and retirement time. Start with one concurrent heavy startup/refresh lane as a conservative scheduling policy, not a measured performance claim. Independently ready workspaces must still serve requests.

Retain the released per-worker heap default and support explicit workspace overrides through the catalogue. Use a declared aggregate reservation ceiling and resident-worker limit before starting another monolith. Heap reservations are not total-memory guarantees: account for native/process overhead and separately selected Gradle memory in the policy, and report what was reserved versus observed. Unknown footprint must not be described as a proven memory fit.

Default to one resident worker as well as one heavy startup/refresh lane. This is a conservative initial admission policy, not a claim that one monolith fits every machine. Multiple workspaces remain registered and addressable; concurrent residency requires an explicit worker count and aggregate reservation/headroom policy. Use a two-worker configuration for the cross-workspace acceptance case. This avoids inventing a universal percentage-of-RAM heuristic while permitting deliberate multi-monolith operation. Capacity shortage results in a bounded, visible queue or typed rejection. No automatic eviction of active work, repeated restart thrashing, or uncontrolled retry loop. Idle retirement requires no active operation or unresolved effect and a declared policy.

Multiplexed transport does not imply concurrent IntelliJ mutation. Keep one active semantic execution lane per workspace initially, using the existing read/write authority. Allow concurrency between workspaces, and make later intra-workspace read parallelism a separately evidenced optimization.

### 5.4 Bound waits and preserve uncertainty

Use distinct budgets for connection handshake, workspace startup, capacity queueing, operation execution, and cleanup. Queueing and operation budgets have an explicit aggregate interaction bound; each stage consumes remaining allowance rather than renewing it. Carry constrained remaining time across processes, not process-local clock timestamps.

Replace unbounded frame and dispatch waits at the actual CLI/indexer boundaries. Prove that timeout closes owned blocking I/O and reaches supported request cancellation; merely adding a coroutine timer is insufficient. Slow or partially initialized connections cannot monopolize indexer acceptance.

A request timeout must not stop the shared workspace runtime. Explicit cancellation retires only the request whose identity and authority match. If semantic work cannot be proven retired, keep that workspace lane unavailable for conflicting work and report `RecoveryRequired`; other workspaces remain usable. Never convert timeout into an empty successful answer or a replay authorization.

Expose installation, service generation, connection, thread, workspace, runtime generation, request phase, queue age, elapsed/remaining allowance, applied heap, and last finite failure through bounded diagnostics. Separate socket reachability, coordinator readiness, workspace readiness, and per-request progress. Do not log source or tool payloads. Optimize expensive capture/import/index phases only after this evidence locates a bottleneck.

## 6. Delivery graph and focused proofs

Implement through the existing modules and Gradle gates, not a new orchestration framework. The following edges are the work graph; derive execution order from them.

| Task | Dependencies | Deliverable and allowed write boundary | Focused proof |
| --- | --- | --- | --- |
| C1 Configuration contract | none | Owner declarations, shared pure values, generated projections, CLI inspection, build-policy checks. | Every existing consumed setting has an owner/projection; unknown/invalid/test-only input rejects; active and desired values differ visibly. |
| I1 Installation ownership | C1 | Distribution layout/manifest, installer, existing broker/sidecar launch paths, stop/upgrade/reset transitions. | Two isolated installations, stale handshake, ownership-safe retirement, upgrade, and reset leave no reusable old endpoint or unlisted Kast state. |
| W1 Workspace binding | C1 | App-server registry, protocol binding, provider invocation context, existing session tests. | Two connections with reused IDs and two workspaces route correctly; ambiguous selection and stale bindings reject. |
| W2 Workspace supervision | I1, W1 | Coordinator reservations, existing sidecar admission, workspace queues, ingestion/refresh lifecycle. | Concurrent demand coalesces; an import in A does not block ready B; capacity is bounded; no duplicate worker. |
| L1 Request lifetime | W2 | CLI/indexer transport and existing semantic cancellation/telemetry boundaries. | Silent/partial peers and cancelled queued/executing reads terminate within declared bounds; resources retire or uncertainty remains local. |
| A1 Installed acceptance | I1, W1, W2, L1 | Existing packaging fixtures, documentation, required build checks and evidence. | Real installed clients complete correctly routed semantic calls across two workspaces, plus retirement/reset acceptance. |

Each node includes its tests with the implementation. RED must fail a named behavioral assertion before the fix, not fail because a proposed class is missing. GREEN must execute that assertion unchanged. Short test deadlines are injected typed policy, not shortened production semantics. Failure cases use disposable processes/homes and never alter the user's live installation.

Proposed focused test selectors, to be added in their existing owning modules:

```sh
./gradlew :distribution:contract:test --tests '*ConfigurationContractTest'
./gradlew :cli:test --tests '*ConfigurationInspectionTest'
./gradlew :distribution:managed:test --tests '*InstallationOwnershipTest'
./gradlew :app-server:test --tests '*VersionedServiceLifecycleTest'
./gradlew :app-server:test --tests '*MultiWorkspaceRoutingTest'
./gradlew :app-server:test --tests '*WorkspaceAdmissionTest'
./gradlew :cli:test --tests '*WireDeadlineTest'
./gradlew :indexer:test --tests '*IndexerRequestLifecycleTest'
```

Retain released regression tests, run architecture verification, and use the existing `productBuildGate` at the final implementation head. Integrate one multi-workspace installed fixture into existing packaging checks; do not multiply expensive monolith runs across the unit-test suite. Generated configuration outputs and required gate commands must be checked into the implementation with their exact task names. The commands above name proposed tests, not tests executed for this planning change.

For completed implementation nodes, record exact source head, configuration/catalogue digest, declared input and command identities, predecessor evidence, observed assertions, and produced artifacts in existing report mechanisms. A changed implementation/input invalidates relevant evidence. Reports are not manually editable completion flags and this Markdown file is not a receipt.

## 7. Acceptance and stopping condition

| Requirement | Acceptance evidence |
| --- | --- |
| R1 Complete installation ownership | Inspect/reset/remove enumerate the version root and every external anchor; old services cannot resurrect; a second installation and external homes remain untouched. |
| R2 Transparent configuration | Catalogue covers all Kast-owned ingress and operational limits; reports show provenance and desired/applied state with secrets redacted; malformed configuration does not disable passive inspection. |
| R3 Correct routing | Multiple clients and interleaved threads use two registered workspaces correctly, including duplicate IDs, reconnect, root aliases, and explicit ambiguity rejection. |
| R4 Stable monolith ingestion | Concurrent requests share one startup per workspace; cold/importing A does not block ready B; resource admission prevents unbounded simultaneous workers/imports. |
| R5 Bounded interaction | Real socket stall/cancellation tests terminate, preserve certainty distinctions, and do not retire unrelated work or the whole service. |
| R6 Reversible operation | Partial startup, disconnected frontend, controlled upgrade, and reset converge to one known owner or a precise blocked state; no generic cache wipe or broad process kill is needed. |
| R7 Preserved semantics | Contained-link, heap, generation, isolation, and mutation-replay regressions pass without weakening authority. |

Installed acceptance uses two independent Gradle workspaces, real admitted client connections, and completed semantic results. Exercise delayed ingestion in one while the other is ready, then disconnect/reconnect a client, retire the installation, and restart from its retained desired configuration. Capture actual workspace/result identities and owner generations, not only process creation or catalogue discovery.

Separately validate one representative heavy monolith on an authorized machine with explicit heap/resource policy, existing network/trust settings, and no proprietary payloads in the report. Small fixtures prove routing/lifecycle behavior; they do not prove monolith-scale capacity. Missing private-host access is an explicit unverified gate, not a reason to invent a sizing result or block independent implementation.

Finish with a clean-checkout build at the final head, required CI, full-diff review, and an R1-R7 evidence table. Record remaining blocked gates. Documentation completion, implementation completion, and observed heavy-workload qualification are distinct outcomes.

## Sources

Kast references are pinned to the inspected head. Source observations in section 1 are facts; the architecture, layout, commands, and test selectors that follow are proposed changes. Checked-in documentation/code are authoritative here; the published documentation aggregate was unavailable during this pass.

[install]: https://github.com/amichne/kast/blob/660efa785f4c8498def2ce13409cbc632315cb1f/install.sh
[broker]: https://github.com/amichne/kast/blob/660efa785f4c8498def2ce13409cbc632315cb1f/app-server/src/main/kotlin/io/github/amichne/kast/appserver/PersistentBrokerService.kt
[host]: https://github.com/amichne/kast/blob/660efa785f4c8498def2ce13409cbc632315cb1f/app-server/src/main/kotlin/io/github/amichne/kast/appserver/MacOsPersistentBrokerServiceHost.kt
[enrollment]: https://github.com/amichne/kast/blob/660efa785f4c8498def2ce13409cbc632315cb1f/app-server/src/main/kotlin/io/github/amichne/kast/appserver/WorkspaceEnrollment.kt
[routing]: https://github.com/amichne/kast/blob/660efa785f4c8498def2ce13409cbc632315cb1f/app-server/src/main/kotlin/io/github/amichne/kast/appserver/protocol/codex/CodexProtocolAdapter.kt
[hub]: https://github.com/amichne/kast/blob/660efa785f4c8498def2ce13409cbc632315cb1f/app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/BrokerSessionHub.kt
[composition]: https://github.com/amichne/kast/blob/660efa785f4c8498def2ce13409cbc632315cb1f/cli/src/main/kotlin/io/github/amichne/kast/cli/bootstrap/InstalledKastCliComposition.kt
[sockets]: https://github.com/amichne/kast/blob/660efa785f4c8498def2ce13409cbc632315cb1f/cli/src/main/kotlin/io/github/amichne/kast/cli/runtime/RuntimeBoundary.kt
[wire]: https://github.com/amichne/kast/blob/660efa785f4c8498def2ce13409cbc632315cb1f/cli/src/main/kotlin/io/github/amichne/kast/cli/UnixDomainWireClient.kt
[indexer]: https://github.com/amichne/kast/blob/660efa785f4c8498def2ce13409cbc632315cb1f/indexer/src/main/kotlin/io/github/amichne/kast/indexer/InstalledIndexerTransport.kt
[budgets]: https://github.com/amichne/kast/blob/660efa785f4c8498def2ce13409cbc632315cb1f/protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/OperationExecutionBudget.kt

Governing design guidance: [Kast engineering dictum](https://github.com/amichne/kast/blob/660efa785f4c8498def2ce13409cbc632315cb1f/AGENTS.md) and [Slopsentral semantic ratchet](https://github.com/amichne/slopsentral/blob/main/source/skills/semantic-ratchet/SKILL.md).

End of plan. No product implementation or runtime acceptance is asserted by this document.
