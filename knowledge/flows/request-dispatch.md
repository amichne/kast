---
type: Runtime Flow
title: Request dispatch
description: Hosted requests retain canonical outcomes through native evaluation, bounded encoding and host projection.
resource: file://runtime/hosted
tags: [runtime, protocol, dispatch]
timestamp: 2026-09-16T00:00:00Z
code_sources:
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/provider/KastDirectInvocation.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/provider/KastInvocationAdmission.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/BrokerLifecycleApprovals.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedReadCompletion.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedReadDeadline.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedReadTransaction.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedDiagnosticCompletion.kt
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
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/CanonicalQueryProtocol.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryReferenceAuthority.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedEndpointProtocol.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedReadBudgetAdmission.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedCanonicalQuery.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/ide/ExistingIdeQuery.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/ide/ExistingIdeSocketClient.kt
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
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/protocol/codex/CodexOwnedSchemaInventory.kt
    symbols: [CodexOwnedSchemaInventory]
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/protocol/codex/CodexCompiledSchemaInventory.kt
    symbols: [CodexCompiledSchemaInventory]
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/protocol/codex/CodexToolCallProjection.kt
    symbols: [CodexToolCallProjector, CodexThreadHistoryProjector]
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/BrokerSessionActivity.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/BrokerSessionHub.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/protocol/codex/CodexPlanApprovalProjection.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/HostedPlanApprovalGateway.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/ide/HostedChangeCliInput.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/ide/HostedChangeEvidence.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedResponse.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedSemanticServices.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedChangeFailure.kt
---

# Request dispatch

```text
host request
  -> canonical operation lookup
  -> typed wire decoding
  -> necessary response-byte admission
  -> existing-IDE handler selection
  -> domain operation
  -> typed complete / qualified / rejected outcome
  -> host projection
```

Registry construction proves that every canonical operation has one definition. Wire-table construction proves that each has one serializer binding. Hosted dispatch uses those typed bindings; `HostedSemanticServices` supplies the request-scoped domain services. Host adapters may change presentation, but they must preserve qualification and rejection.

For the five reads, a supplied byte limit below the serialized wire schema and
operation identity rejects before handler selection. No semantic grant is invented
for this rejection. Satisfying that necessary lower bound does not establish that a
complete response body can fit; encoded output remains the publication authority.

Diagnostic dispatch selects caller-elapsed completion in the existing deadline
owner. Semantic admission captures a request-local completion proof with the exact
effective elapsed allowance. Projection, retained-output restoration and encoding
run inside that allowance, followed by final host freshness validation and the
completion check before publication. Exhaustion or a regressed clock rejects with
`BUDGET_EXCEEDED` and the actual admitted report; this is distinct from a resumable
diagnostic stage stop. Other read operations retain their existing host-containment
completion policy. Host timeout and cancellation drainage remain independently
owned by the existing executor.

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

App Server reads `share/kast/provider-catalog.json` and qualifies projection version 15
against the canonical registry, packaged operation schemas, and declared default budgets.
Qualification and provider startup never launch Kast; startup rereads the catalog and
rejects digest drift. Installed payload admission and Codex schema qualification remain separate. Canonical registry input aliases
resolve to the selected preferred tool route; omitted tools and incompatible
catalog bindings reject before provider invocation. Provider invocation uses the App Server-owned
IDEA socket client directly, with canonical admission and admitted output and elapsed-time settings. The installed coordinator first prepares the exact root, validates the live application/project identity, and retains that project through descriptor admission. Preparation failures are known pre-execution rejections with typed causes and operation IDs.
Approval preparation uses the same direct client; exact approval binding precedes effects.
Pure request preparation and outcome projection are shared from `protocol:wire`. `selectCliRuntimePath` now selects the seven existing-IDE reads before
installed bootstrap in `KastCliMain`; saved read settings are admitted before the socket is opened. Invalid settings and missing hosts remain distinct rejections. The
[native acceptance review](../../docs/reviews/live-semantic-read-acceptance.md)
records the final distribution's complete/qualified native matrix and a successful
production provider invocation with direct CLI evidence equality. The provider
harness does not establish full Codex WebSocket or multi-client acceptance.

Broker-owned dynamic tools project into the schema-admitted `mcpToolCall` display
shape in live and history carriers while retaining the original dynamic fields.
Codex schema inventory admits the retired `thread/rollback` request and response
schemas only as a complete pair; a client without both leaves that route as an
unchanged upstream pass-through while `thread/revert` remains qualified. Their
raw content remains intact, and the final Kast JSON envelope additionally
uses the supported structured-result field. For change apply/recovery,
`CodexPlanApprovalProjection` emits a separate native `fileChange` item containing
the stored plan's preview and requests approval from the current controller.
`HostedPlanApprovalGateway` signs only a correlated controller-approved challenge;
the tool-result projection and approval preview remain distinct. Native schema
admission and module tests establish the protocol shapes, while desktop rendering
remains unqualified.

The entire `change` CLI family routes to the existing-IDE path before installed
bootstrap. Hosted ingress admits `AddDeclaration` planning and requires the
broker's approved invocation for apply/recovery. The endpoint protocol is version
3. Its added change routes retain canonical complete/qualified/rejected envelopes;
missing hosts, unsupported intents and unapproved writes cannot fall back to an
isolated worker. A stored verified apply receipt is historical evidence, so its
original host may differ from the current endpoint; current reads still require
the admitted endpoint owner.

See [protocol](../modules/protocol.md) and [operation outcomes](../contracts/operation-outcomes.md).

The current [public tool contracts](../contracts/public-tools.md) distinguish presentation identity from canonical operation identity. Three ordinary searches and deferred `query_symbols` share `query.run`; `check_diagnostics` shares `diagnostic.check`. Private admission retains each tool's schema identity and typed syntax through canonical request preparation. The `tool` command family uses the existing-IDE read path. Operation effects, budgets, reference authority and exhaustive outcomes remain with their existing owners.

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

`HostedResponse` carries the original typed semantic outcome beside the encoded document until transport ends. Completion, qualification and rejection have separate endpoint observations. Encoding rejection and size rejection retain the semantic value for diagnosis. Read transaction success alone is `EVALUATED`; the canonical path additionally retains semantic `COMPLETE`, `QUALIFIED` or `REJECTED` evidence. Change-storage failures retain their finite cause in schema-admitted `HOST_REJECTED.detail` and in default bounded storage observations.

Model-facing Codex replies retain one JSON envelope as their final content item.
Compact source presentation can precede it with unchanged returned source text.
Terminal broker admission,
capacity and cancellation paths also retain typed JSON rejection or cancellation
data; uncertainty is not converted into success. A supported native desktop
display receives the same bounded result as structured content. Display parsing
cannot alter execution authority or the broker's recovery settlement.

An existing-host read failure carries an execution report only after semantic budget admission. Timeout and final revalidation preserve the report together with the finite failure and stage; encoded-output and retention refusal preserve the report at the publication boundary. CLI schema admission then refines the report through the canonical decoder before forwarding the hosted failure document.

Compact source results retain ordered source and structure sections through CLI
schema admission. The source-specific production-provider presenter emits the
unchanged returned source first and the structured result afterward. Other
operation presentations retain their existing dispatch behavior.

Workspace refresh is a separate typed hosted control path, not a canonical semantic read. Its request and response DTOs retain request identity and finite pending, complete, failed, rejected or configured outcomes. The CLI uses typed serialization for the hosted transport, validates the independent refresh schema, and binds the response to the admitted host and root.

Agent lifecycle dispatch calls the App Server-owned lifecycle client and selected application control endpoint directly. For `request_user_close`, the existing controller lease receives a native command-approval item naming the exact host, project incarnation and root. Only one acceptance for that invocation permits enrolled signing; session-wide acceptance does not. The invocation transports the signed assertion privately and preserves finite lifecycle blockers through the output schema. Native semantic dispatch remains passive; the coordinator may prepare the workspace through the lifecycle owner before sending a semantic request.

Local controller claim/release enters the owned daemon management route and delegates
to the existing session owner. The target service generation and connected observer
membership are checked before control can change. Native Codex session requests
cannot claim or release another connection by supplying its identifier. Management
inspection observes pending/prepared/rejected/closed session state without admitting
an optional host.

Apply and recovery approval challenges share the installed workspace preparation owner. A workspace rejection is projected before controller registration, preserving its operation identity and finite cause. Controller approval and grant redemption remain separate from workspace readiness.

Invocation admission persists intent before approval preparation or workspace
submission. Known approval refusals, binding rejections and queued cancellations
are durably completed before their responses are published. Executing calls settle
inside the workspace permit so persistence failure remains uncertain before the
lane can advance. The response owner publishes once, preserves uncertain outcomes,
and keeps active results separate from its bounded completed cache. Eviction
removes response bytes only; the durable fingerprint and phase continue to reject
replay and conflicting inputs.

Thread-store migration preserves historical bindings as non-executable records.
Resume, fork and tool dispatch retain `NEW_CONVERSATION_REQUIRED` rather than
silently rebinding those identities. New conversations receive current records;
missing or corrupt unrelated historical records do not enter an exact thread
lookup. Store failures preserve their finite cause through the owning projection.

Private update preparation reads the live session, invocation, workspace and
preparation owners under the same admission mutex used by frontend creation,
session ingress and management mutations. A quiescent seal rejects new work while
passive status remains available. Pending upstream requests survive frontend
detachment; transport retirement with an unresolved request retains uncertainty
and cannot supply a quiescence proof. Status observation never repeats preparation
or seals admission.
