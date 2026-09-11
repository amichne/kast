---
type: Kotlin Module Group
title: Protocol
description: Canonical operation contracts are completed by an exact registry and projected into generated wire documents.
resource: file://protocol
tags: [kotlin, protocol, serialization]
timestamp: 2026-09-11T00:00:00Z
code_sources:
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolContract.kt
  - path: protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/CanonicalAgentToolDefinitions.kt
  - path: docs/reviews/live-semantic-read-acceptance.md
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/CanonicalOperation.kt
    symbols: [CanonicalOperation]
  - path: protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/OperationRegistry.kt
    symbols: [OperationRegistry, OperationRegistryConstruction]
  - path: protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/CanonicalOperationDefinitions.kt
  - path: protocol/wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/OperationWireTable.kt
    symbols: [OperationWireTable]
  - path: protocol/wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/OperationWireBinding.kt
  - path: protocol/wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/WireEnvelope.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/CanonicalSourceReadOperationModels.kt
  - path: protocol/contract/src/main/resources/ide-hosted/hosted-endpoint.schema.json
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/bootstrap/InstalledServerProjectionDocuments.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/provider/KastProvider.kt
  - path: query/protocol/build.gradle.kts
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/CanonicalChangeOperationModels.kt
---

# Protocol

Protocol has three layers:

1. `protocol:contract` owns the closed public operation set and transport-independent request/outcome models.
2. `protocol:registry` requires exactly one fully typed definition for every canonical operation, with unique operation and schema identities.
3. `protocol:wire` requires exactly one serializer binding per canonical operation and projects canonical documents.

Unknown identities remain explicit unknown results; neither registry nor wire construction manufactures authority for them. See [operation registry](../contracts/operation-registry.md) for the exact completeness invariant.

Semantic-read admission and projection live in the separate
[`query:protocol` module](query-protocol.md). Hosts supply current authority and
domain operations to that reusable boundary.

Successful wire envelopes preserve the distinction between a published
generation and live IDE provenance. Decoding rejects a success envelope with
both or neither evidence basis. Source snapshot documents likewise distinguish
published source-state identity from the live saved, PSI-committed view. A live
stamp is never serialized as a workspace generation or source-state identity.

The canonical read revisions are `query.run.v2`, `source.read.v4`, and version 3
for symbol discovery, symbol inspection, relation reads, traversal, and diagnostics.
The CLI's App Server projection is version 10, and provider qualification requires
that version. Its successful read schemas carry mutually exclusive published and
live variants, including the corresponding source snapshot shape. The hosted
endpoint schema is version 3 and advertises the seven canonical read routes plus
change planning, approval preparation, apply and recovery. Version-2 endpoint
descriptors reject. `change.apply.v3` distinguishes `Verified`,
`AppliedUnverified` and `RecoveryRequired`; only the first carries a receipt.
Qualified effects retain their finite reason and exact plan identity.
The earlier provider-qualified CLI schema document was 290,635 bytes, below its
524,288-byte qualification cap. Hosted read admission now also has a closed
`CONFIGURATION_REJECTED` outcome; canonical semantic outcome schemas retain their existing identities. Schema compatibility and native execution remain
separate evidence: the [acceptance review](../../docs/reviews/live-semantic-read-acceptance.md)
records the final default-route CLI matrix and actual provider invocation.

The current [public tool contracts](../contracts/public-tools.md) distinguish presentation identity from canonical operation identity. Three ordinary searches and deferred `query_symbols` share `query.run`; `check_diagnostics` shares `diagnostic.check`. Private admission retains each tool's schema identity and typed syntax through its exact CLI binding. The `tool` command family uses the existing-IDE read path. Operation effects, budgets, reference authority and exhaustive outcomes remain with their existing owners.
