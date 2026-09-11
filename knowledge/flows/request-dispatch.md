---
type: Runtime Flow
title: Request dispatch
description: A host request is qualified against the canonical registry, decoded by its wire binding, dispatched through runtime composition, and projected without weakening its semantic outcome.
resource: file://runtime/server
tags: [runtime, protocol, dispatch]
timestamp: 2026-09-10T00:00:00Z
code_sources:
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

See [protocol](../modules/protocol.md) and [operation outcomes](../contracts/operation-outcomes.md).
