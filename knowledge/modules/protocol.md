---
type: Kotlin Module Group
title: Protocol
description: Canonical operation contracts are completed by an exact registry and projected into generated wire documents.
resource: file://protocol
tags: [kotlin, protocol, serialization]
timestamp: 2026-09-09T00:00:00Z
code_sources:
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/CanonicalOperation.kt
    symbols: [CanonicalOperation]
  - path: protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/OperationRegistry.kt
    symbols: [OperationRegistry, OperationRegistryConstruction]
  - path: protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/CanonicalOperationDefinitions.kt
  - path: protocol/wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/OperationWireTable.kt
    symbols: [OperationWireTable]
---

# Protocol

Protocol has three layers:

1. `protocol:contract` owns the closed public operation set and transport-independent request/outcome models.
2. `protocol:registry` requires exactly one fully typed definition for every canonical operation, with unique operation and schema identities.
3. `protocol:wire` requires exactly one serializer binding per canonical operation and projects canonical documents.

Unknown identities remain explicit unknown results; neither registry nor wire construction manufactures authority for them. See [operation registry](../contracts/operation-registry.md) for the exact completeness invariant.
