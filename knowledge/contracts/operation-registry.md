---
type: API Contract
title: Canonical operation registry
description: The public operation set is closed, and successful registry and wire construction prove exact complete coverage with unique identities.
resource: file://protocol/registry
tags: [protocol, registry, schema]
timestamp: 2026-09-09T00:00:00Z
code_sources:
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/CanonicalOperation.kt
    symbols: [CanonicalOperation, CanonicalOperationResolution]
  - path: protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/OperationRegistry.kt
    symbols: [OperationRegistry, OperationRegistryFailure]
  - path: protocol/wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/OperationWireTable.kt
    symbols: [OperationWireTable, OperationWireTableFailure]
---

# Canonical operation registry

`CanonicalOperation` is the only public operation identity set. Resolution preserves unknown identities as `Unknown`.

Successful `OperationRegistry.create` proves:

- exactly one fully typed definition for every canonical operation;
- unique operation identifiers;
- unique schema identities across distinct operations; and
- canonical ordering.

Successful wire-table construction separately proves one serializer binding per operation and unique schema binding. Both construction paths return closed failure sets instead of partial registries.
