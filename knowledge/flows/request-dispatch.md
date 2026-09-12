---
type: Runtime Flow
title: Request dispatch
description: A host request is qualified against the canonical registry, decoded by its wire binding, dispatched through runtime composition, and projected without weakening its semantic outcome.
resource: file://runtime/server
tags: [runtime, protocol, dispatch]
timestamp: 2026-09-12T00:00:00Z
code_sources:
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolContract.kt
  - path: protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/CanonicalAgentToolDefinitions.kt
  - path: docs/reviews/live-semantic-read-acceptance.md
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/ide/ExistingIdeCli.kt
    symbols: [selectCliRuntimePath]
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/bootstrap/KastCliMain.kt
  - path: protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/OperationRegistry.kt
    symbols: [OperationRegistry]
  - path: protocol/wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/OperationWireTable.kt
    symbols: [OperationWireTable]
  - path: runtime/server/src/main/kotlin/io/github/amichne/kast/runtime/server/ServerDispatch.kt
    symbols: [ServerDispatch]
  - path: runtime/composition/src/main/kotlin/io/github/amichne/kast/runtime/composition/KastOperationHandlerFactory.kt
    symbols: [KastOperationHandlerFactory]
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/CanonicalQueryProtocol.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryReferenceAuthority.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedEndpointProtocol.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedCanonicalQuery.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/ide/ExistingIdeQuery.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/ide/ExistingIdeSocketClient.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/provider/KastProvider.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/core/Broker.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/core/BrokerOperationEffect.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/protocol/codex/SettledOutputRejection.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/WorkspaceExecution.kt
  - path: app-server/src/test/kotlin/io/github/amichne/kast/appserver/runtime/SettledReadOutputContractTest.kt
  - path: app-server/src/test/kotlin/io/github/amichne/kast/appserver/runtime/OutputContractRecoveryPolicyTest.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/schema/JsonSchemaViolationEvidence.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/protocol/codex/BrokerFailureDocument.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/protocol/codex/CodexProtocolAdapter.kt
    symbols: [CodexProtocolAdapter]
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/protocol/codex/CodexToolCallProjection.kt
    symbols: [CodexToolCallProjector, CodexThreadHistoryProjector]
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/BrokerSessionActivity.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/BrokerSessionHub.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/protocol/codex/CodexPlanApprovalProjection.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/HostedPlanApprovalGateway.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/ide/HostedChangeCliInput.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/ide/HostedChangeEvidence.kt
---

# Request dispatch

```text
host request
  -> canonical operation lookup
  -> typed wire decoding
  -> runtime handler selection
  -> domain operation
  -> typed complete / qualified / rejected outcome
  -> host projection
```

Registry construction proves that every canonical operation has one definition. Wire-table construction proves that each has one serializer binding. Server dispatch uses those typed bindings; runtime composition supplies the owning domain handler. Host adapters may change presentation, but they must preserve qualification and rejection.

Canonical semantic-read handlers delegate request admission, reference codecs,
and outcome projection to [`query:protocol`](../modules/query-protocol.md).
The host remains responsible for current workspace or live IDE authority and
budgets. Shared protocol code does not launch workers or admit projects.

The default hosted dispatch has a closed request case and canonical wire binding
for each of the seven public semantic reads. The existing-IDE client uses the same
operation-specific decoders, rejects published evidence, and checks a successful
live envelope against the requested root and admitted descriptor host. A typed host
rejection can be returned before read authority exists; it does not become a
successful canonical payload.

App Server provider qualification requires projection version 10 and its exact
operation schemas and declared default budgets. Invocation continues through the configured CLI
process with admitted output and elapsed-time settings. `selectCliRuntimePath` now selects the seven existing-IDE reads before
installed bootstrap in `KastCliMain`; saved read settings are admitted before the socket is opened. Invalid settings and missing hosts remain distinct rejections. The
[native acceptance review](../../docs/reviews/live-semantic-read-acceptance.md)
records the final distribution's complete/qualified native matrix and a successful
production provider invocation with direct CLI evidence equality. The provider
harness does not establish full Codex WebSocket or multi-client acceptance.

Broker-owned dynamic tools retain their original live and history documents.
The former MCP display relabeling has been removed. For change apply/recovery,
`CodexPlanApprovalProjection` emits a separate native `fileChange` item containing
the stored plan's preview and requests approval from the current controller.
`HostedPlanApprovalGateway` signs only a correlated controller-approved challenge;
the original dynamic item remains unchanged. Native schema admission and module
tests establish the protocol shapes, while desktop rendering remains unqualified.

The entire `change` CLI family routes to the existing-IDE path before installed
bootstrap. Hosted ingress admits `AddDeclaration` planning and requires the
broker's approved invocation for apply/recovery. The endpoint protocol is version
3. Its added change routes retain canonical complete/qualified/rejected envelopes;
missing hosts, unsupported intents and unapproved writes cannot fall back to an
isolated worker. A stored verified apply receipt is historical evidence, so its
original host may differ from the current endpoint; current reads still require
the admitted endpoint owner.

See [protocol](../modules/protocol.md) and [operation outcomes](../contracts/operation-outcomes.md).

The current [public tool contracts](../contracts/public-tools.md) distinguish presentation identity from canonical operation identity. Three ordinary searches and deferred `query_symbols` share `query.run`; `check_diagnostics` shares `diagnostic.check`. Private admission retains each tool's schema identity and typed syntax through its exact CLI binding. The `tool` command family uses the existing-IDE read path. Operation effects, budgets, reference authority and exhaustive outcomes remain with their existing owners.

The broker validates each encoded provider result against its qualified output
schema before presentation. Output-contract rejection retains a deduplicated set
of closed schema-keyword and field observations. Unknown fields remain `UNKNOWN`;
validator messages, source values and reference tokens do not enter this evidence.
Serializable failure DTOs preserve existing failure and correction fields and
carry these observations as `outputViolationEvidence`.

Kast provider qualification retains the canonical `OperationEffect` in the broker
tool. After `ProviderCall.Completed`, a schema-invalid result still rejects that
invocation. If its admitted effect is `NONE` or `INTELLIJ_READ`, the adapter marks
the invocation's effects known and the workspace lane serves its next request.
Output validity and semantic success remain separate from this settlement decision.

All writing effects and unknown provider effects retain recovery-required
handling after output rejection. Provider failures, cancellation, and timeouts
remain uncertain even for reads; read metadata cannot prove that an execution has
terminated. Deterministic gates test queued and later calls, mutation without
replay, independent workspaces, and retirement held across cancellation/deadline.
