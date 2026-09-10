---
type: Runtime Flow
title: Request dispatch
description: A host request is qualified against the canonical registry, decoded by its wire binding, dispatched through runtime composition, and projected without weakening its semantic outcome.
resource: file://runtime/server
tags: [runtime, protocol, dispatch]
timestamp: 2026-09-09T00:00:00Z
code_sources:
  - path: protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/OperationRegistry.kt
    symbols: [OperationRegistry]
  - path: protocol/wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/OperationWireTable.kt
    symbols: [OperationWireTable]
  - path: runtime/server/src/main/kotlin/io/github/amichne/kast/runtime/server/ServerDispatch.kt
    symbols: [ServerDispatch]
  - path: runtime/composition/src/main/kotlin/io/github/amichne/kast/runtime/composition/KastOperationHandlerFactory.kt
    symbols: [KastOperationHandlerFactory]
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

See [protocol](../modules/protocol.md) and [operation outcomes](../contracts/operation-outcomes.md).
