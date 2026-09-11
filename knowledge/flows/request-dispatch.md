---
type: Runtime Flow
title: Request dispatch
description: A host request is qualified against the canonical registry, decoded by its wire binding, dispatched through runtime composition, and projected without weakening its semantic outcome.
resource: file://runtime/server
tags: [runtime, protocol, dispatch]
timestamp: 2026-09-11T00:00:00Z
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
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/protocol/codex/CodexProtocolAdapter.kt
    symbols: [CodexProtocolAdapter]
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/protocol/codex/CodexToolCallProjection.kt
    symbols: [CodexToolCallProjector, CodexThreadHistoryProjector]
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/BrokerSessionActivity.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/BrokerSessionHub.kt
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

App Server provider qualification requires projection version 9 and its exact
operation schemas and declared default budgets. Invocation continues through the configured CLI
process with admitted output and elapsed-time settings. `selectCliRuntimePath` now selects the seven existing-IDE reads before
installed bootstrap in `KastCliMain`; saved read settings are admitted before the socket is opened. Invalid settings and missing hosts remain distinct rejections. The
[native acceptance review](../../docs/reviews/live-semantic-read-acceptance.md)
records the final distribution's complete/qualified native matrix and a successful
production provider invocation with direct CLI evidence equality. The provider
harness does not establish full Codex WebSocket or multi-client acceptance.

Broker-owned dynamic tools retain their execution protocol and upstream history.
At the downstream display boundary, `CodexToolCallProjector` uses the standard
`mcpToolCall` item with raw arguments and `result.content` text because the desktop
client's generic dynamic-tool row omits result content. The same projection covers
live notifications and reloaded history, retains the original outcome fields, and
rejects contradictory status/success evidence. Qualification admits both shapes
against the selected binary's generated schemas. `tool_display` session activity
records projection completion or rejection without source payloads. See the
[App Server presentation contract](../../app-server/README.md#presentation-and-evidence)
for the raw display and its verification limits.

See [protocol](../modules/protocol.md) and [operation outcomes](../contracts/operation-outcomes.md).

The current [public tool contracts](../contracts/public-tools.md) distinguish presentation identity from canonical operation identity. Three ordinary searches and deferred `query_symbols` share `query.run`; `check_diagnostics` shares `diagnostic.check`. Private admission retains each tool's schema identity and typed syntax through its exact CLI binding. The `tool` command family uses the existing-IDE read path. Operation effects, budgets, reference authority and exhaustive outcomes remain with their existing owners.
