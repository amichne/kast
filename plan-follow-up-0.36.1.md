# Follow-up plan: bounded queries, enrolled sessions, and MCP schema compatibility

Status: proposed implementation handoff; this document is not proof that the fixes pass.

Baseline: `amichne/kast` `main` and release tag `v0.36.1` both resolve to `660efa785f4c8498def2ce13409cbc632315cb1f` at inspection on 2026-09-08. The preceding symlink/heap work landed in PR #694. Preserve those changes and their regression tests; do not reopen them without new evidence. [S1]

Scope: the newly reported query stall, incomplete workspace enrollment/session binding, and MCP output-schema interoperability. Implement these as separate changes joined by installed acceptance. This follow-up does not replace the native Codex integration with MCP or introduce multi-repository routing implicitly.

## 1. Evidence and limits

The following observations come from the supplied screenshot reports, not from a reproduction performed while writing this plan. Enterprise paths, repository names, task IDs, and screenshots are deliberately not included in this public document.

| ID | Reported observation | What it does and does not establish |
| --- | --- | --- |
| O1 | The disposable MCP adapter derived seven tools from `serverProjection.hostedBootstrap.tools`; `initialize` and `tools/list` succeeded after omitting `outputSchema`. | Demonstrates discovery for that adapter/client combination. Does not validate the omitted output contract or a completed semantic call. |
| O2 | The MCP call spawned `kast query run`; the process remained waiting for several minutes without a result. The report says `bootstrap.state=ready`, `cache-state=smart`, and substantial indexer CPU activity. | Establishes a reported liveness failure. Spawning a process alone does not prove correct stdin, working root, output handling, or where the wait occurs. CPU activity and passive readiness do not establish per-request progress. |
| O3 | The report compared the wait with advertised `operationMillis: 60000`. | Requires separate accounting for readiness, queueing, operation execution, and response transport. The exact request, elapsed trace, and effective client timeout were not supplied. |
| O4 | An earlier diagnostic call returned `bootstrap-attempt-lock-unavailable` under a read-only sandbox. | A reported permission/admission failure, not evidence that the sandbox should be disabled. Locate the actual denied runtime-owned path before prescribing access. |
| O5 | A Codex session reached the broker with working directory `/`, no workspace enrollment, no Kast thread binding, no registered task/controller, and no Kast tools. | Consistent with an incomplete launch path. Successful transport qualification is not successful session enrollment. A control claim cannot create a missing task binding. |
| O6 | Enrollment is described as one workspace per `CODEX_HOME`; two repositories cannot be selected interchangeably through that one record. | A current architectural constraint. A shared parent directory is not an equivalent semantic workspace. Multi-repository support is a separate product change. |
| O7 | A connected task reader reportedly showed empty item text while persisted conversation history contained completed responses. | A presentation/history discrepancy to reproduce. It does not establish that Kast lost history or justify rewriting Codex's conversation store. |

Source inspection confirms these narrower findings:

- `KastCli.executeRequest()` calls `WireClient.exchange(endpoint, document)` without a deadline. `UnixDomainWireClient` uses blocking connect/write/read operations; its finite failure set has no timeout. Its current bounds are frame-size bounds, not elapsed-time bounds. [S2] [SKASTCLI]
- `InstalledIndexerTransport` serves connections serially, reads frames without an elapsed bound at this boundary, and awaits dispatch using `CompletableFuture.join()`. These waits are concrete locations to bound and instrument; they do not identify which one caused O2. [S3]
- `OperationExecutionBudget.SEMANTIC_READ` is 60,000 ms, but `WORKSPACE_READINESS` is 1,020,000 ms. `invocation` adds them, and `KastProvider` passes that aggregate to its process executor. Thus the native provider's configured outer allowance is 18 minutes; it is not a standalone 60-second operation-phase guard. The disposable adapter's policy remains unknown. [S4] [SPROVIDER]
- `runInstalledCodex()` ensures the persistent service and then attaches a client. It does not establish workspace enrollment. `AppServerAction.Enable` performs enrollment but also inspects desktop eligibility, installs login configuration, and changes GUI daemon opt-in. Do not call that entire management action automatically from every CLI launch. [S5] [SHOST]
- `CodexProtocolAdapter.threadStart()` forwards requests outside enrollment, including absent/incompatible `cwd`, without injecting Kast tools. Enrollment is read at broker construction; management status separately reads the on-disk record. A disk record and a live broker acknowledgement must not be confused. [S6] [SSERVER]
- The installed server output schema is a union of object-shaped `completed/document` and `rejected/diagnostic` variants. This is the hosted invocation envelope, not the raw semantic CLI document. [S7]

External protocol verification adds an important qualification to O1: MCP 2025-11-25 requires `Tool.outputSchema.type` to be `object`; the 2026-07-28 schema permits any valid JSON Schema 2020-12 output schema. Record the actual client/SDK and negotiated version rather than asserting that all MCP versions prohibit a root union. [M25] [M26]

## 2. Required outcomes and non-goals

| Requirement | Required outcome |
| --- | --- |
| R1 | The direct CLI returns a terminal, typed response or failure within its admitted phase bounds, even when a peer accepts a connection and never responds. |
| R2 | Readiness and operation budgets are distinct. Queueing and transport cannot silently renew the operation allowance. Timeout/cancellation reaches owned work and has bounded cleanup. |
| R3 | Durable request evidence identifies the last reached boundary without recording source, arguments, results, credentials, or unbounded logs. |
| R4 | A Kast-required launch establishes one canonical workspace, matching enrollment, and a live qualification acknowledgement before creating a Kast-enabled thread. |
| R5 | No enrollment, conflicting enrollment, malformed state, and missing thread binding remain distinct finite states. Existing unrelated/native Codex sessions are not silently retargeted. |
| R6 | The running broker, injected catalog, persisted thread binding, and invocation root agree. A file on disk or ready socket alone cannot prove that agreement. |
| R7 | The observed older MCP client can discover a fully described output contract without dropping closed variants or changing the result value merely to satisfy a root-schema restriction. |
| R8 | Semantic read authority remains distinct from runtime provisioning effects. Permission failures reject with actionable bounded evidence rather than escalating privileges. |
| R9 | Installed acceptance exercises a completed semantic call and bounded failure, not only discovery or process creation. Desktop/UI and private-workload claims require separate actual evidence. |
| R10 | The v0.36.1 contained-link, heap, isolation, exact-root, and mutation-replay guarantees remain intact. |

Do not increase timeouts or heap defaults to mask O2; rebuild/reseed as a generic response; kill the shared indexer or unrelated Gradle daemons to cancel one call; introduce automatic mutation retries; store conversation history in Kast; maintain a handwritten second tool catalog; enroll `/` or a common parent to cover independent repositories; or copy the local launcher shim into Kast as a second lifecycle authority.

## 3. Architecture and authority

Retain the existing physical ownership boundaries. This is not a module reorganization. [S8]

| Owner | Responsibility in this change | Forbidden authority |
| --- | --- | --- |
| `:kernel` / existing pure contracts | Reuse constrained durations, identities, and closed outcomes; add a shared value only when a real boundary needs it. | Filesystem, process, clock sampling, IntelliJ, or environment access. |
| `:protocol:registry` and wire contracts | Canonical operation budgets and any explicitly versioned request metadata needed for propagation. | Client-specific timeout defaults or an independent operation registry. |
| `:cli` | Raw invocation/configuration admission, runtime demand, terminal CLI projection, and bounded UDS effects. | Declaring compiler success from socket reachability. |
| `:app-server` | Enrollment, live session admission, catalog/thread binding, provider process supervision, and host projections. | Importing CLI implementation or IntelliJ/runtime implementations. |
| `:indexer` | Connection/frame lifetime, request cancellation, and dispatch supervision. | Killing the service to retire a single read or inventing semantic results. |
| `:runtime:composition` and existing semantic adapters | Route request context to the operation owner; cancel/release semantic work at the existing effect boundary. | Reusing stale model evidence to make a deadline pass. |
| Existing Gradle verification/packaging owners | Focused tests, deterministic schemas, installed acceptance, and exact-head evidence. | A second build orchestrator or manually editable completion flags. |

The process graph remains:

```text
CLI launch -> admitted workspace -> enrollment/live broker proof -> Codex client
                                                          -> thread/catalog binding
                                                          -> native tool invocation
                                                              |
Direct CLI ---------------------------------------------------+-> runtime demand
Disposable MCP adapter ---------------------------------------+-> bounded wire exchange
                                                                  -> indexer dispatch
                                                                  -> semantic outcome
                                                                  -> bounded response
```

MCP and Codex are transport/host projections. Neither is the semantic authority. Enrollment grants a workspace route; it is not evidence that a query has completed.

Use private constructors and the existing `Refinement` conventions for values such as `AdmittedOperationDeadline`, `ResolvedWorkspace`, and `QualifiedWorkspaceSession`. These names are design suggestions, not existing APIs. Do not reduce them to raw paths plus `enrolled=true` or raw milliseconds plus `timedOut=false`. Clock readings and process/socket effects stay at explicit adapters. Preserve `Complete`, `Qualified`, and `Rejected` semantic outcomes independently of transport failure and invocation certainty. [S11]

## 4. Canonical task graph

| Task | Dependencies | Deliverable |
| --- | --- | --- |
| Q0 | none | Portable stall reproduction and bounded request-stage evidence. |
| Q1 | Q0 | Enforced direct-CLI readiness/operation/transport deadlines. |
| Q2 | Q1 | Indexer cancellation, bounded admission/dispatch, and owned cleanup. |
| E1 | none | Typed launch-root and enrollment admission. |
| E2 | E1 | Live enrollment acknowledgement and required thread/catalog binding. |
| M1 | none | Object-root-compatible output projection with semantic-equivalence tests. |
| A | Q2, E2, M1 | Installed native/MCP acceptance, documentation, review, and requirement revalidation. |

Dependency waves must be derived from these edges; do not maintain a second wave list. Q0's portable reproduction must not require enterprise logs, so their absence cannot block the independent lanes.

For each node below, the listed files/modules are its allowed writes. Reads may include their dependencies, governing guides, tests, and canonical schemas. Additional writes require an identified necessity, not convenience. Each task produces an exact-head evidence receipt; no task is complete merely because its implementation exists.

### Q0 — Locate the wait and establish request evidence

**Goal and inputs.** Reproduce the liveness gap safely using the current CLI/UDS path, an admitted small request, and controllable peers. The original private request and disposable adapter source are not available; record that rather than reconstructing them from a truncated screenshot.

**Allowed writes and outputs.** Existing CLI/indexer transport tests and the narrow existing diagnostic/telemetry boundaries. Output a red regression plus typed, correlated stages: runtime demand started/ready, connection opened, frame sent/received, server admitted/queued, dispatch started/completed, response written, caller terminal, and cleanup terminal. Reuse existing request identities where possible. Record elapsed time, operation, remaining allowance, and finite stage outcomes; never log payloads.

**Public/internal interface.** Version any public diagnostic/schema additions through the existing owner. The internal observer receives closed stage events, not exception-message protocols. Publish progress to stderr/private telemetry, not to the semantic stdout document or MCP protocol stdout.

**Effect/cost.** Bounded local socket I/O and diagnostic writes; no real Gradle import is required for the portable regression.

**RED/GREEN.** Proposed test class, added before the fix:

```sh
./gradlew :cli:test --tests '*WireDeadlineTest'
```

A peer accepts a complete request and withholds a response. RED is a failed bounded-completion assertion, not an indefinitely hung test or a missing test class. The harness has its own finite deadline and closes its owned sockets in `finally`. Include partial-header and partial-payload variants. Stage-evidence tests become GREEN here; the deadline regression remains RED until Q1.

**Review boundary and receipt.** Confirm that the trace distinguishes waiting for readiness from waiting after readiness. Bind the safe request fixture, exact argv/stdin contract, test command, and observed failed assertion. A real-workload trace can be added later without blocking this task.

### Q1 — Enforce deadlines in the direct CLI path

**Goal.** Make R1/R2 true without depending on an MCP wrapper or the native provider's aggregate process watchdog.

**Allowed writes.** `KastCli.kt`, `UnixDomainWireClient.kt`, their request/exit contracts and tests; canonical budget/wire metadata only where propagation requires it. Keep the existing operation budget authority in `OperationExecutionBudget`. [S2] [S4]

**Design.** The readiness phase has its admitted maximum. Once this call has obtained `RuntimeAdmission.Ready`, start its operation deadline before connection/write/response work. A ready call must not receive an undocumented additional readiness allowance for semantic execution. Bound readiness itself and report its phase explicitly.

Connect, frame write, header read, payload read, and caller-side queueing consume the same remaining operation allowance. Bound lengths and elapsed time independently. Preserve the deadline in `WireClient`/`WireSession` signatures so an unlimited call cannot accidentally be reconstructed. Use JDK deadline-capable I/O or cancellation that actually closes the owned blocking channel; merely placing a blocking read inside `withTimeout` is not sufficient proof.

The caller's monotonic deadline owns its terminal-response bound. Where remaining time crosses processes, transmit a constrained remaining allowance, not process-local monotonic timestamps. Account for server queue time and couple caller expiry to connection/request cancellation; do not reset every stage to a fresh 60 seconds. Document bounded scheduling/cleanup tolerance separately from the operation allowance.

**Outputs/public interface.** Finite deadline/cancellation failures with operation and last reached stage, exhaustive exit/schema projection, and a closed terminal outcome. Timeout is not an empty semantic answer and does not establish absence of effects. Preserve mutation uncertainty and durable replay fences.

**Effect/cost.** Bounded UDS and runtime-demand orchestration. No semantic algorithm change and no cache rebuild.

**RED/GREEN.** Re-run Q0's unchanged test command. Add connect/write stalls, truncated responses, successful responses, explicit cancellation, resource closure, and no budget renewal. Pure deadline tests use a controllable clock; actual socket tests prove real interruption without large timing benchmarks. GREEN requires the caller to terminate and its own resources to retire.

**Review/receipt.** Trace one direct CLI request from admission to terminal output. Bind phase-budget tests and actual socket evidence. Do not claim Q2's server-work retirement from a client-only timeout test.

### Q2 — Bound server work and retire only the owned request

**Goal.** A timed-out or abandoned read cannot leave an unbounded dispatch or idle client monopolizing the server's admission path.

**Allowed writes.** `InstalledIndexerTransport.kt`, `IndexerWireFrameCodec.kt`, indexer host/dispatch adapters, and the existing semantic cancellation boundary and tests. Adapt the wire contract explicitly if necessary. [S3]

**Design.** Replace unbounded connection/frame and dispatch waits with a request-owned lifetime. Preserve the intended serialization of semantic work; do not accidentally grant concurrent IntelliJ mutation by making socket admission concurrent. A bounded connection reader/queue may admit independently of the single semantic owner.

Expose separate accepted, queued, executing, cancellation-requested, and retired evidence. Queue time consumes budget. Late/duplicate responses cannot complete a different request. Client EOF/timeout must retire its request ownership, propagate cancellation into supported IntelliJ/Gradle read mechanisms, and release permits/read leases.

Do not use unsafe thread termination. If an underlying operation cannot be proven retired, keep that uncertainty visible and do not release a conflicting semantic capability as though cleanup succeeded. The caller must still receive its bounded failure; server health/reconciliation is a distinct outcome. No timeout handler may stop the shared sidecar or shared Gradle daemon.

**Outputs/interface/effect.** Closed dispatch/cleanup outcomes and correlated lifecycle evidence. Effect ownership remains in indexer and existing semantic adapters; cost is bounded request supervision, not a new traversal or runtime engine.

**RED/GREEN.** Proposed focused tests:

```sh
./gradlew :indexer:test --tests '*IndexerRequestLifecycleTest'
```

Cover a silent/partial-frame client, a deliberately suspended dispatch, cancellation during queueing/execution, disconnect after submission, late completion, and a second request after proven retirement. Use deterministic coordination. GREEN proves a subsequent valid request works and owned work has retired; it must not pass because the test killed the entire server.

**Review/receipt.** Include the server cancellation trace and permit/resource-release proof. Investigate the actual high-CPU workload only after the trace identifies its expensive phase; do not attribute it to new symlink hashing, a leak, or seeding without measurements.

### E1 — Admit a canonical launch workspace and enrollment decision

**Goal.** Eliminate the path that launches an apparently Kast-enabled session after checking only that the broker process exists.

**Allowed writes.** App-server host admission/launcher code, `WorkspaceEnrollment.kt`, narrow installed CLI composition, and relevant tests. The local `kodex` shim remains a delegator, not a policy owner. [S5] [SHOST] [SENROLL]

**Inputs and root policy.** Parse the existing client's explicit `-C`/`--cd` options once. Explicit directory wins; otherwise use the caller's physical working directory and Kast's existing workspace-root admission. Any proposed fixed-root setting is subordinate to an explicit request, not an instruction to silently replace it. Reject conflicting/malformed explicit options before effects.

Canonicalize once and preserve the result. A Git root is a useful candidate, not proof of a supported Gradle workspace. Do not introduce an unconditional `pwd -P` fallback that enrolls `/`, a parent of independent builds, or an arbitrary directory. A nested working directory may map to its already-enrolled workspace; distinguish client working directory from semantic invocation root.

**Closed policy.** For a Kast-required ordinary CLI launch, select `Ensure` by default: absent enrollment may be created for the admitted root; matching enrollment is reused; another workspace or corrupt record rejects. `Verify` permits no enrollment write. Informational invocations such as help/version do not enroll or start a service. Explicit stop/disable/suppression must not be silently overridden by an ordinary attachment.

Enrollment is distinct from enabling GUI/login integration. Factor the narrowly authorized enrollment operation from `AppServerAction.Enable`; do not install a LaunchAgent or change GUI daemon environment simply because a CLI session needs a workspace route. Any persistent-service action must retain its existing ownership and stop/disable rules. [S5]

**Outputs/public/internal interface.** Produce an admitted launch value carrying canonical workspace identity, requested client directory, enrollment policy, and permitted effects. Do not add arbitrary environment flags or a second root resolver in shell. Preserve finite reasons such as workspace conflict, missing enrollment in verify mode, corrupt enrollment, invalid workspace, and stopped service.

**Effect/cost.** Pure admission plus bounded canonical filesystem reads; enrollment writes only through the existing explicit store boundary.

**RED/GREEN.** Proposed launch tests plus existing store tests:

```sh
./gradlew :app-server:test --tests '*WorkspaceLaunchAdmissionTest' --tests '*WorkspaceEnrollmentTest'
```

Cover root/subdirectory/alias, absent enrollment, matching enrollment, a second repository, corrupt record, duplicate/conflicting options, informational commands, and explicit stop/disable. GREEN rejects unsupported states before a client process or enrollment mutation occurs.

**Review/receipt.** Prove one owner for root selection and enrollment decision; bind argv-admission and no-effect tests. Do not convert single-workspace enrollment into a registry in this task.

### E2 — Require live enrollment and per-thread catalog proof

**Goal.** The broker servicing the thread must acknowledge the same enrollment the launcher admitted, and a Kast-required thread must receive the catalog and binding before it is reported enabled.

**Allowed writes.** `InstalledBrokerServer.kt`, enrollment/service management, `CodexProtocolAdapter.kt`, session/status projections, and their tests. Keep conversation history owned by Codex. [S6]

**Design.** Handle the already-running-unenrolled broker explicitly. A write to `workspace.json` is insufficient because the broker captures enrollment at construction. Use a service-owned, serialized activation/acknowledgement boundary that verifies the canonical store and acknowledges its effective enrollment. Do not broaden this into hot retargeting: absent-to-enrolled activation is allowed only with compatible existing binding state; a conflicting enrolled root still rejects. Preserve in-flight unrelated/native work and existing service ownership.

The qualification receipt binds service generation/identity, canonical workspace, effective enrollment, schema/catalog digests, and host protocol qualification. Launch with an explicit admitted client directory. At thread creation, verify the resulting thread directory, injected catalog digest, durable binding, and task/controller registration. Required Kast admission must reject on failure rather than falling through to a successful but unbound thread.

Do not reject every unrelated Codex thread globally. Preserve explicit native pass-through behavior for clients/threads that have not requested Kast authority, and expose their unbound state honestly. The current adapter's generic forwarding behavior is intentional; the required-launch path needs stronger admission. [S6]

An unknown task has no controller lease to claim. Report that state and direct recovery to a fresh correctly enrolled thread. Do not fabricate bindings for an old unbound session or promise post-start tool injection. Qualify start/resume/fork behavior against the installed Codex-generated schemas; documentation for another version is not that executable's contract. [S9] [OAI]

**Outputs/interface/effect.** A proof-carrying live workspace/session admission; finite activation/binding failures; status that separates transport, protocol, catalog, effective enrollment, thread binding, semantic readiness, and desktop qualification. Bounded store/process/protocol effects remain in app-server adapters.

**RED/GREEN.** Proposed tests:

```sh
./gradlew :app-server:test --tests '*EnrolledSessionLaunchTest' --tests '*InstalledBrokerServerTest'
```

Include a live broker started before enrollment, same-root idempotence, conflicting roots, canonical aliases, missing or `/` directory on a required launch, corrupt state, catalog drift, native pass-through, and an unknown control claim. GREEN requires live acknowledgement plus a bound new thread; status reading a newly written file cannot satisfy it.

**Review/receipt.** Trace enrollment through the actual service generation and thread binding. Preserve native requests, approval ownership, and content item identity. Record the O7 history mismatch as unresolved unless a pinned-client reproduction demonstrates and locates a Kast projection defect.

### M1 — Preserve output contracts while satisfying the observed MCP client

**Goal.** Remove the schema-advertisement incompatibility without a second tool catalog or weaker output validation.

**Allowed writes.** The installed output-schema generator, its provider/schema parity tests and generated fixtures, and a repository-local disposable MCP interoperability test if needed. A production MCP server or new CLI command is not implicitly authorized by this plan. [S7]

**Design.** All currently advertised hosted outcomes are objects. Prefer the smallest semantics-preserving correction: retain the existing union and add the explicit root `type: object` through an object-union constructor at the appropriate generator boundary. Leave generic unions that can legitimately describe non-objects unchanged.

Conceptually, the schema becomes `type: object` alongside the same `anyOf` branches. Keep definitions/references and closed property constraints intact. Do not wrap the runtime result in an extra `result` property merely for this requirement; do not flatten the variants into unrelated nullable fields; do not omit the output schema in the supported integration.

Preserve the distinction between the hosted invocation envelope and the inner semantic document. `KastProvider` wraps CLI success as `completed/document` and CLI boundary failure as `rejected/diagnostic`; an adapter promising that schema cannot return a bare CLI document instead. An inner qualified/rejected semantic result is not automatically an MCP transport failure. Explicitly test `structuredContent`, textual compatibility output, and error mapping against the declared schema. [S7] [SPROVIDER]

Pin the actual MCP client/SDK and negotiated version used by the reproduction. An object-root schema remains valid for the broader newer contract; do not advertise support for a newer complete protocol merely because its tool schema validates. Version the installed projection only according to the existing compatibility policy, and update its decoder expectations and fixtures together.

**Effect/cost.** Pure schema generation/validation and a bounded test-only process/protocol adapter. Do not introduce new semantic effects. Derive tool names, descriptions, request schemas, and CLI bindings from the canonical projection. The seven reported tools are observational evidence, not a new hard-coded catalog. The read-only POC must deny write-capable or unknown effects before process creation using the canonical effect/approval policy; an MCP annotation is a hint, not execution authority. Do not label runtime provisioning as effect-free merely because the semantic operation is a read.

**RED/GREEN.** Existing and proposed tests:

```sh
./gradlew :cli:test --tests '*InstalledServerProjectionTest'
./gradlew :app-server:test --tests '*McpOutputSchemaCompatibilityTest' --tests '*KastProviderTest'
```

RED reproduces the older client validator's root-type rejection. GREEN retains `outputSchema`, accepts the same valid hosted outcomes, rejects invalid mixed variants, and preserves qualified/rejected inner outcomes. Test the root-shape transformation independently of Q1/Q2. Discovery is not the completed-call acceptance gate.

**Review/receipt.** Bind the original and generated schema digests, protocol/client versions, positive/negative validation corpus, and discovery output. No MCP success claim may depend on having removed the advertised output contract.

### A — Installed acceptance, documentation, and final review

**Dependencies.** Q2, E2, and M1. This is the join, not permission to absorb adjacent features.

**Allowed writes.** Existing installed-product/host fixtures, narrowly scoped interoperability fixtures, troubleshooting/configuration/compatibility documentation, and evidence definitions owned by those gates.

Use a disposable supported Gradle workspace with a known declaration and retained valid internal Gradle links. Confirm the configured sidecar heap from its actual startup observation; do not infer that a running JVM changed because the caller environment changed. Reuse the v0.36.1 fixture rather than building an enterprise-sized CI benchmark. [S10]

Required installed journeys:

1. Direct CLI: perform one warm query for the known declaration and validate the returned compiler-grounded result. Inject a nonresponding peer/stalled request; require a finite timeout and owned cleanup; then complete another request. Report readiness and operation elapsed values separately.
2. Native Codex: begin without enrollment through the supported Kast-required launch. Prove canonical root, effective live enrollment, thread/catalog binding, controller registration, and one completed tool call. Repeat with an already-running unenrolled broker. Exercise conflict, corrupt state, catalog drift, and unknown-task rejection without rewriting state.
3. MCP test adapter: use a pinned client, retain `outputSchema`, complete initialize/discovery as required by that protocol version, and execute the same small valid request to a schema-valid terminal result. Keep stdin/stdout/protocol framing correct, bound output, propagate cancellation, and retire only adapter-owned invocation processes. Do not change global Codex configuration.
4. Restricted access: provision the runtime through an authorized setup path, then test semantic access under the intended sandbox. A denied bootstrap/lock write must fail finitely with the correct boundary; do not weaken the sandbox or claim semantic-read permission implies provisioning permission.

Real desktop rendering/history and the affected private workload are separately named qualification gates. Discovery, a stand-in desktop process, and unit tests do not qualify them. Preserve the existing `desktop: unqualified` reporting until a real authorized UI journey passes. On an O7 reproduction, compare native `thread/read`/resume output with what the client renders before attributing the mismatch. [S9]

## 5. Verification graph, receipts, and stop condition

The task commands above name proposed tests where those tests do not yet exist. Implement each test before using it as RED evidence. A missing test, compilation typo, unsupported host, or missing credential is not the expected behavioral failure.

Use Gradle's existing graph and gate ownership. `productBuildGate` already joins subproject checks and product verification; do not create a parallel delivery framework. Extend existing typed convention/task inputs and schema generation only where these requirements need new evidence. [S12]

At the implementation head, run focused changed-boundary tests first, then the required architecture/product checks:

```sh
./gradlew verifyKastArchitecture
./gradlew --max-workers=2 productBuildGate
```

Use existing installed gate owners (`installedProductTest`, `installedCodexHostTest`) for the added journeys. Do not execute them twice when the required aggregate already includes them. Any newly required interoperability gate must have one declared Gradle owner. Derive dependency order from task inputs/edges rather than duplicating it in a shell script.

Receipts must identify the program/requirement fingerprint, exact implementation head, base revision, predecessor receipt digests, declared input and command digests, observed proof values, and artifact digests. Schema-backed deterministic projections must distinguish `PASS` from `UNVERIFIED`, `BLOCKED`, or `FAIL`; only `PASS` satisfies a required gate. A changed head/input/command invalidates evidence that no longer binds it. Do not commit a receipt claiming to prove its own future commit SHA; emit verification artifacts outside the source commit they attest to.

| Requirement | Implementation and proof owner |
| --- | --- |
| R1 | Q1; actual blocking-peer CLI tests and installed direct query. |
| R2 | Q1/Q2; budget propagation, server retirement, and subsequent-request tests. |
| R3 | Q0/Q2; correlated stage/terminal evidence and payload-exclusion tests. |
| R4 | E1/E2; launch admission and live qualification tests. |
| R5 | E1/E2; conflict, corruption, unbound task, and native pass-through tests. |
| R6 | E2; live activation acknowledgement, catalog digest, root, and binding parity. |
| R7 | M1/A; pinned-client schema validation, discovery with schema retained, and completed call. |
| R8 | E1/A; no-effect admission and restricted-access boundary tests. |
| R9 | A; installed terminal-result journeys, separate private/desktop qualification evidence. |
| R10 | Required product checks and retained v0.36.1 regression fixtures. |

Final acceptance requires a detached clean checkout of the exact candidate head, required CI at that same head, full-diff self-review plus an independent review, resolution of valid findings, and revalidation against R1-R10. Rerun affected proof after final edits and complete the required exact-head checks before claiming release readiness. No manually checked box or task count substitutes for those results.

If private-workload access, an authenticated client, or authorized desktop interaction is unavailable, complete the independent implementation/tests and record the exact blocked qualification. Do not turn a blocked gate into PASS or claim the original user's workload is repaired solely because the portable fixture passes.

## 6. Explicitly separate subsequent work

Multi-repository service support is not a prerequisite for this fix. Keep one canonical enrollment per `CODEX_HOME` and reject conflicts visibly. If commissioned later, its authority must be a service-owned registry of exact canonical workspaces, with each thread bound to exactly one workspace and catalog generation, per-workspace execution ownership, and an explicit migration from the singleton record. It must not be emulated by changing a global root between concurrent sessions or enrolling their parent directory.

Likewise, shipping a supported MCP server is a separate feature decision. This plan fixes schema compatibility and proves a bounded adapter journey without treating a disposable POC as a production lifecycle implementation.

## Sources

All Kast code links are pinned to the inspected release/head. User-reported observations O1-O7 are intentionally separate from code/specification findings.

[S1]: https://github.com/amichne/kast/commit/660efa785f4c8498def2ce13409cbc632315cb1f
[S2]: https://github.com/amichne/kast/blob/660efa785f4c8498def2ce13409cbc632315cb1f/cli/src/main/kotlin/io/github/amichne/kast/cli/UnixDomainWireClient.kt
[S3]: https://github.com/amichne/kast/blob/660efa785f4c8498def2ce13409cbc632315cb1f/indexer/src/main/kotlin/io/github/amichne/kast/indexer/InstalledIndexerTransport.kt
[S4]: https://github.com/amichne/kast/blob/660efa785f4c8498def2ce13409cbc632315cb1f/protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/OperationExecutionBudget.kt
[S5]: https://github.com/amichne/kast/blob/660efa785f4c8498def2ce13409cbc632315cb1f/app-server/src/main/kotlin/io/github/amichne/kast/appserver/AppServerManagement.kt
[S6]: https://github.com/amichne/kast/blob/660efa785f4c8498def2ce13409cbc632315cb1f/app-server/src/main/kotlin/io/github/amichne/kast/appserver/protocol/codex/CodexProtocolAdapter.kt
[S7]: https://github.com/amichne/kast/blob/660efa785f4c8498def2ce13409cbc632315cb1f/cli/src/main/kotlin/io/github/amichne/kast/cli/bootstrap/InstalledServerProjectionDocuments.kt
[S8]: https://github.com/amichne/kast/blob/660efa785f4c8498def2ce13409cbc632315cb1f/app-server/README.md
[S9]: https://github.com/amichne/kast/blob/660efa785f4c8498def2ce13409cbc632315cb1f/app-server/docs/compatibility.md
[S10]: https://github.com/amichne/kast/blob/660efa785f4c8498def2ce13409cbc632315cb1f/docs/public/troubleshooting.mdx
[S11]: https://github.com/amichne/kast/blob/660efa785f4c8498def2ce13409cbc632315cb1f/AGENTS.md
[S12]: https://github.com/amichne/kast/blob/660efa785f4c8498def2ce13409cbc632315cb1f/build.gradle.kts
[M25]: https://modelcontextprotocol.io/specification/2025-11-25/schema#tool
[M26]: https://modelcontextprotocol.io/specification/2026-07-28/schema#tool
[OAI]: https://learn.chatgpt.com/docs/app-server

[SKASTCLI]: https://github.com/amichne/kast/blob/660efa785f4c8498def2ce13409cbc632315cb1f/cli/src/main/kotlin/io/github/amichne/kast/cli/KastCli.kt
[SPROVIDER]: https://github.com/amichne/kast/blob/660efa785f4c8498def2ce13409cbc632315cb1f/app-server/src/main/kotlin/io/github/amichne/kast/appserver/provider/KastProvider.kt
[SHOST]: https://github.com/amichne/kast/blob/660efa785f4c8498def2ce13409cbc632315cb1f/app-server/src/main/kotlin/io/github/amichne/kast/appserver/KastCodexMain.kt
[SENROLL]: https://github.com/amichne/kast/blob/660efa785f4c8498def2ce13409cbc632315cb1f/app-server/src/main/kotlin/io/github/amichne/kast/appserver/WorkspaceEnrollment.kt
[SSERVER]: https://github.com/amichne/kast/blob/660efa785f4c8498def2ce13409cbc632315cb1f/app-server/src/main/kotlin/io/github/amichne/kast/appserver/InstalledBrokerServer.kt

Process guidance: [Slopsentral semantic ratchet](https://github.com/amichne/slopsentral/blob/main/source/skills/semantic-ratchet/SKILL.md). The published Kast `llms-full.txt` endpoint could not be retrieved during this pass; checked-in documentation at the pinned head was used instead.

End of plan. Implementation and runtime qualification remain unverified until the gates above produce evidence.
