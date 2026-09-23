---
type: Kotlin Module Group
title: Runtime and process hosts
description: The existing IDEA plugin owns semantic execution; CLI and App Server own installation, transport, sessions and approval.
resource: file://runtime
tags: [kotlin, runtime, server, indexer, cli]
timestamp: 2026-09-16T00:00:00Z
code_sources:
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/SessionRequests.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/DaemonUpgradeAdmission.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/DaemonUpgradeGate.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/DaemonUpgradeDocument.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/DaemonUpgradeObservation.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/BrokerSessionMessages.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/WorkspaceExecutionPolicy.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/protocol/FileThreadCatalogStore.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/protocol/ThreadBindingDocuments.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/protocol/ThreadCatalogStore.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/protocol/ThreadMigrationObservation.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/InvocationResponses.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/InvocationCapacityLimit.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/InvocationFence.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/InvocationRecords.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/FileInvocationRecords.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/storage/PrivateRecordFiles.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/InvocationMigrationObservation.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/InstalledWorkspacePreparation.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/WorkspaceDemand.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/WorkspacePreparations.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/WorkspacePreparation.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/DaemonWorkspacePreparation.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/WorkspacePreparationActivity.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/DaemonManagementProtocol.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/InstalledDaemonManagementClient.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/InstalledDaemonUpgrade.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/DaemonManagement.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/CoordinatorRoutes.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/provider/KastCatalogObservation.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/provider/KastInputSchema.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/provider/KastCatalogSource.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/provider/KastDirectInvocation.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/provider/KastInvocationAdmission.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/lifecycle/IdeLifecycleApplication.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/WorkspaceStartupEnrollment.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/BrokerPublicEndpoint.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/PersistentBrokerService.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/NativeCodexReadiness.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/InstalledCoordinatorClient.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/ConfigurationAppliedInspection.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/AppServerStatus.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/AppServerStatusDocument.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedConnectionAdmission.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedTransportObservation.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedReadBudgetReports.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedQueryContinuations.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolContract.kt
  - path: protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/CanonicalAgentToolDefinitions.kt
  - path: docs/reviews/live-semantic-read-acceptance.md
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/InstalledCoordinator.kt
    symbols: [InstalledCoordinator]
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/bootstrap/KastCliMain.kt
    symbols: [KastCliMain]
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedEndpointService.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/OwnedHostedEndpoint.kt
  - path: runtime/hosted/build.gradle.kts
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/compatibility/IdeHostCompatibility.kt
    symbols: [IdeHostCompatibilityPolicy, IdeReleaseLine]
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedCanonicalQuery.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedEndpointProtocol.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedPeerCancellation.kt
    symbols: [HostedPeerTermination, dispatchUntilPeerTermination]
  - path: query/protocol/build.gradle.kts
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/ide/ExistingIdeCli.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/ide/ExistingIdeSocketClient.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/ide/ExistingIdeQuery.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/provider/KastProvider.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/command/ide/IdeCommands.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedChangeCoordinator.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/ide/BrokerTrustEnrollment.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedResponse.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedSemanticServices.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/CoordinatorControl.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedReferenceStore.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedQueryResponse.kt
---

# Runtime and process hosts

`runtime:hosted` is the sole production semantic host. Its project service uses the already open IDEA project and delegates admission, model capture, and read epochs to `workspace:intellij-read`. IDEA owns imports and incremental indexes. Kast has no isolated indexer, second workspace importer, index-copy path, or topology backend in the shipped runtime graph. The retained topology modules and their separate SQLite adapter remain buildable for upcoming work.

The plugin archive contains the semantic contracts, services, IntelliJ adapters and durable change stores. Its name binds the product version and IDEA release line. Runtime admission retains exact observed IDEA/Kotlin identities while allowing the configured release-line compatibility policy. The historical [native acceptance review](../../docs/reviews/live-semantic-read-acceptance.md) states the tested baseline; a build or schema check alone does not expand that qualification.

`HostedSemanticServices` composes request-scoped symbol, source, relation and diagnostic services from one admitted project/read context. Canonical queries, planning and post-write verification share this factory. It does not retain a project read across requests or supply project-opening authority.

`HostedResponse` retains the original complete, qualified or rejected outcome alongside its encoded document. Encoding and response-size failures retain the original semantic result. The endpoint observer reports semantic classification rather than treating every written frame as completion. [Hosted queries](../flows/hosted-query.md) describes lifetime and cancellation.

The CLI admits local metadata and broker configuration separately from its existing-IDE semantic route. All canonical reads, public intent tools and supported changes use the plugin. A missing host rejects. Bare `kast` reports the product version, existing-IDE authority and passive root discovery; `kast ide status` observes the project endpoint. Retired `start` and `stop` commands cannot launch a worker.

App Server owns persistent sessions, the invocation journal, controller approvals, provider qualification and workspace lanes. `CoordinatorControl` provides bounded owner-correlated status with zero worker reservations and rejects retired worker demands. New Codex threads automatically persist an unregistered canonical working directory (or explicit containing root) before binding. Existing containing registrations are reused. Registration preserves closed failures and emits bounded, payload-free startup evidence. Thread-binding validation for resume and invocation remains read-only. Workspace enrollment remains routing data. It grants no importer or worker capability. Provider qualification verifies the packaged catalog against the canonical registry. Installed semantic provider calls first use the shared workspace preparation owner. They then use the App Server-owned IDEA client directly; approval challenges use the same workspace demand before immutable plan loading, preserving canonical request admission, finite failures, root/host binding and operation output validation. Pure request and result projection lives in `protocol:wire`.

Planning stores immutable live plans; applying and recovering require the exact controller-approved plan and current native admission. Installation owns automatic trust enrollment and preserves existing valid keys; the transitional `kast ide trust-broker` command uses the same owner. Apply never creates trust. Read [request dispatch](../flows/request-dispatch.md) and [change lifecycle](../flows/change-lifecycle.md) for the complete boundaries.

The [installed knowledge contract](../contracts/installed-knowledge.md) describes
`kast knowledge`, its isolated PSI extraction, verified module ownership and
scoped guide resources staged with the control product.

HostedSemanticServices projects the context’s admitted time allowance into query and diagnostic-scope budgets; configured capacities remain separately available. Schema-4 receipts retain the effective allowance, remaining host time, completion reserve and finite admission outcome.

`HostedReferenceStore` retains at most 16,384 token entries and 32 MiB of token UTF-8 bytes by default, with typed read-limit overrides. It clears the previous table on an admitted epoch change or project disposal. Repeated references reuse the same handle, and current-epoch handles are never evicted for capacity. When a new entry cannot fit, issuance keeps the valid inline token and records `REFERENCE_INLINE_CAPACITY`. No PSI, source payload, or in-process authority is retained in the table.

`HostedQueryResponse` bounds the actual encoded query bytes. An oversized positive result becomes a qualified prefix carrying `BYTE_LIMIT_REACHED`, all existing limitations, the original proven lower bound, and every item failure. If mandatory evidence alone cannot fit, the response remains rejected.

Runtime installation admission uses the shared distribution traversal budget rather than the historical 4,096-path broker ceiling. `PAYLOAD_LIMIT_EXCEEDED` remains distinct through coordinator, service, and client failure projections. Both lifecycle transitions and standalone recovery fences block startup.

`ide status` includes a fresh passive `readiness` observation. `admission_ready`
proves existing-project admission checks at that instant; `unavailable` retains
the closed failure and conditional recovery guidance. It creates no semantic
read permit, source epoch, import, or indexing wait. Query admission still checks
saved content and current authority. Older hosts may omit the new observation;
absence provides no readiness proof.

`HostedQueryContinuations` owns bounded expiring pipeline and encoded-output state
for the current project read authority. Lookup follows fresh host admission and
compares the normalized request and semantic snapshot. Its active epoch owns
five stores: query execution checkpoints and query, source, relation and traversal
output suffixes. Each store has an independent entry, charged-byte and TTL bound;
project disposal or epoch replacement clears all five. Restore does not renew
creation time. It cannot restore a foreign or stale authority. See the
[hosted retention bounds](../flows/hosted-query.md#hosted-continuation-retention-bounds)
for aggregate accounting and the separate native source owner.

Connection admission remains bounded independently of serialized semantic work.
`CONNECTION_RELEASE` is emitted after the admitted connection's semaphore permit
is released, so native fault fixtures can wait for a correlated drain witness
before the next health request. The structured observer retains finite outcomes,
durations and byte counts without request or response payloads. Read rejection
projection preserves any admitted execution report through fitting and encoding.

Read containment keeps the admitted execution report when a timeout, final freshness check or publication failure rejects the operation. The executor records the report at semantic admission; failure projection does not reconstruct it from defaults. Pre-admission failures carry no grant. Output fitting retains the original semantic outcome internally when publishing an oversized or unencodable response fails.

Enabled persistent Codex integration defaults to installation-owned private discovery.
The CLI supplies its endpoint explicitly and the desktop façade attaches to the
same coordinator, so a standalone native Codex daemon can coexist. Explicit
`codex-control` mode selects one canonical public socket per `CODEX_HOME`.
Endpoint policy participates in service identity while the upstream remains private.
Canonical startup completes native initialization before readiness publication;
the private coordinator qualifies host attachment separately. The existing session
hub owns per-client initialization, request correlation, thread/controller ownership
and native tool refinement. An unreachable socket is not ownership proof and cannot
be unlinked by startup. An absent Kast service rejects an occupied canonical endpoint
before sending the Kast status protocol.

Status reports lifecycle, endpoint ownership, native protocol, catalog and upstream
observations separately. Service status requires the requested service identity;
configuration inspection retains the proven incumbent identity so it can report
unapplied configuration changes. Status leaves semantic readiness unobserved rather than
inferring IDEA authority. Installed transport acceptance does not qualify stock
interactive CLI or Desktop tool exposure; the explicit CLI route and Desktop
façade remain pending those gates.

The hosted endpoint also composes an explicit workspace refresh owner outside `workspace:intellij-read`. Its existing socket accepts file refresh, Gradle model reload, bounded status and one per-host opt-in task-success rule. `ide refresh` expose the typed control documents. The legacy refresh request requires an already linked project. Initial linking is admitted only through explicit application lifecycle opening; semantic read admission remains passive.

Fresh semantic reads additionally use `ReacquiringQueryReferences`, backed by the
separate detached exact-locator store. Its request-local accounting charges
recovery work and elapsed time before admitting the remaining semantic budget.
Older-epoch locators can be evicted to retain current handles. Continuation stores
and mutation planning do not use this capability. The
[query protocol](query-protocol.md#automatic-acquisition-for-fresh-reads) specifies
the identity checks and returned handle metadata.

The plugin additionally owns one `IdeLifecycleApplication` service and user-scoped control endpoint per selected graphical application home. It remains available with zero projects, advertises actual build, host incarnation and capabilities, and retains at most 256 operation records. The endpoint reuses existing ownership and framed transport. Project semantic services remain project-scoped. The agent-only `workspace_lifecycle` tool calls the App Server-owned lifecycle client directly and exposes inspect/open/present/sync/release/close/status. `request_user_close` uses the existing controller lease and enrolled signing authority to approve one exact target; session-wide approval is insufficient. Native semantic admission remains passive; the daemon uses opening before semantic dispatch and never obtains user-close authority from that preparation.

App Server qualifies its complete tool catalog from the bounded packaged provider
contract and canonical registry. It has no Kast process executor or CLI version
probe. The provider retains contract identity across qualification and startup;
a changed contract rejects before execution. Approval signing uses the explicitly
admitted user home independently of catalog qualification.

Catalog qualification emits one bounded typed stage/outcome observation per contract
read. It retains source, document, projection, metadata, input-schema and output-schema
failures without logging catalog contents. Request unions are admitted only when
every alternative is a closed object; empty or open alternatives reject.

The coordinator also owns `/kast-management` on the same private Unix socket.
Versioned typed coordinator/session status, registration and controller requests
bypass Codex host admission.
Registration requires the exact installation, epoch, generation and configuration
identity, and checks the existing lifecycle/stopped fence before enrollment.
The CLI verifies published service ownership before requesting registration;
offline or unproven service state cannot fall back to direct registry writes.
Canonical workspace identity and revision survive acknowledgment admission.
Unknown protocols and unobserved replies remain finite failures, with no automatic
retry. Both control routes share the same connection budget. The shared session
owner enforces controller changes against actual connected observers, leases,
active turns and pending approvals. Native clients cannot assert another client's
identity to claim or release control. Public status reads existing session state
passively; it does not initialize or probe Codex. Initial installation bootstrap
retains its existing owner.

The coordinator retains workspace preparation operations independently of Codex
admission and management connection lifetimes. Exact canonical settings roots
coalesce to one request ID and native open operation. Native pending observations
must preserve host/request identity and advance monotonically through opening,
importing and admission. Completed records retain root, host and project identity;
they are historical observations, not authority to skip fresh native admission.
Blocked operations and elapsed deadlines do not automatically replay opening.
A bounded 256-record table rejects excess roots without discarding evidence. Close
settles pending records without closing IDEA projects. Preparation events expose
bounded typed stage/outcome evidence without roots or payloads. Installed tool demand shares this owner and waits within the canonical readiness
budget. Before one semantic exchange, it requires a fresh selected-application
inspection and an exact project descriptor match. A proven host change rejects the
current demand and removes only its current root binding; the historical record
remains. A later demand may prepare again. Transport failures never replay the
semantic request. Preparation rejection retains its finite cause and operation ID
as known pre-execution failure evidence.

Launchd invokes the private daemon entry point without the public CLI command graph. The managed readiness environment is required at ingress and then qualified by the existing coordinator. Exact published-command recovery admits both the private path and the earlier `kast broker serve` form for retirement.
Installation activation and new-release retirement use a separate private
service-control entry point. It admits only enable and disable; older installed
releases retain the admitted public CLI disable path during migration.

Invocation replay evidence lives in private hash-sharded records. The store reads
only the addressed digest, preserves its input fingerprint and finite phase, and
rejects attempts to settle a terminal record or a historical admission owned by a
previous process. Active capacity is separate from durable history; the response
cache evicts only completed results at its separate bound. Durable admission
precedes approvals and workspace submission; known pre-execution rejections settle
before response publication. Duplicate active calls join the original result.
An evicted completed response cannot authorize another prompt or execution. Legacy migration validates and verifies a
locked stage before publishing the layout marker and retains the original
journal. Incomplete migration, unsafe paths, malformed records and lock contention
remain typed failures. Migration observations contain only stage and outcome.

Thread bindings use private digest shards and lazy per-thread validation. A missing
historical workspace does not prevent opening the daemon or using another bound
workspace. Current records retain catalog, working directory, workspace and typed
installation/epoch ownership; conflicting rewrites reject. Locked legacy migration
validates historical identity without claiming current filesystem authority,
verifies a stage, publishes its version marker atomically and retains the source.
Legacy records return `NEW_CONVERSATION_REQUIRED` and cannot be overwritten by a
new binding. Codex history is outside this store. Read/write failures retain their
finite causes through broker projection. Both durable stores share the scoped
`PrivateRecordFiles` effect owner; their record schemas and transition rules remain
separate.

Private update preparation retains a candidate and request identity while active
owners supply finite blockers. Status alone never seals. The quiescence check and
seal share the mutex used by lazy frontend creation, session ingress and management
mutations. A seal rejects new work; cancellation reopens only an uncommitted seal,
and commit is idempotent. Pending upstream requests are bounded and lost requests
retain reconciliation uncertainty. Structured update observations contain finite
stages/outcomes without identities or payloads. The installed management client
qualifies each update reply against the exact daemon target, candidate digest and
request identity; malformed pending blockers and mismatched commit or cancel
responses reject.

The installer-facing upgrade boundary observes the exact launchd label and
retained service markers before requesting a seal. Only absent launchd and absent
markers establish that no managed daemon needs retirement. Active service
admission retains typed blockers and a permit bound to the daemon target,
candidate and request; rejected or ambiguous observations do not authorize
replacement. Installer activation commits that permit before prior-service
retirement. A pending update leaves the selected installation and command links
in place for a later upgrade attempt. If retirement fails after commit, another
ordinary attempt for the same candidate can resume from the active daemon's
qualified committed request. A different candidate or daemon identity rejects.
