---
type: Kotlin Module Group
title: Runtime and process hosts
description: The existing IDEA plugin owns semantic execution; CLI and App Server own installation, transport, sessions and approval.
resource: file://runtime
tags: [kotlin, runtime, server, indexer, cli]
timestamp: 2026-09-14T00:00:00Z
code_sources:
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
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/ide/ExistingIdeSocketClient.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/ide/ExistingIdeQuery.kt
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

App Server owns persistent sessions, the invocation journal, controller approvals, provider qualification and workspace lanes. `CoordinatorControl` provides bounded owner-correlated status with zero worker reservations and rejects retired worker demands. Workspace enrollment remains routing data. It grants no importer or worker capability. Provider calls continue through the exact qualified CLI contract.

Planning stores immutable live plans; applying and recovering require the exact controller-approved plan and current native admission. `kast ide trust-broker` remains the explicit trust-enrollment effect. Read [request dispatch](../flows/request-dispatch.md) and [change lifecycle](../flows/change-lifecycle.md) for the complete boundaries.

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
