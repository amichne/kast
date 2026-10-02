---
type: API Contract
title: Canonical operation registry
description: The public operation set is closed, and successful registry and wire construction prove exact complete coverage with unique identities.
resource: file://protocol/registry
tags: [protocol, registry, schema]
code_sources:
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/CanonicalOperation.kt
    symbols: [CanonicalOperation, CanonicalOperationResolution]
  - path: protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/OperationRegistry.kt
    symbols: [OperationRegistry, OperationRegistryFailure]
  - path: protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/OperationDefinitionFactory.kt
  - path: protocol/wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/OperationWireTable.kt
    symbols: [OperationWireTable, OperationWireTableFailure]
verified:
  - by: openwiki/0.6.1
    at: 2026-10-02T02:00:02.467Z
sources:
  - id: openwiki-source-207ff2d32f1b70dfee08054c
    resource: repo://protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/OperationRegistry.kt
  - id: openwiki-source-73cd922b518ec220021f920f
    resource: repo://protocol/wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/OperationWireTable.kt
generated: { by: "codex", at: "2026-10-02T02:00:02.467Z" }
---

# Canonical operation registry

`CanonicalOperation` is the only public operation identity set. Resolution preserves unknown identities as `Unknown`.

Successful `OperationRegistry.create` proves:

- exactly one fully typed definition for every canonical operation;
- unique operation identifiers;
- unique schema identities across distinct operations; and
- canonical ordering.

Successful wire-table construction separately proves one serializer binding per operation and unique schema binding. Both construction paths return closed failure sets instead of partial registries.
