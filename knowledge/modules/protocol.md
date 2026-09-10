---
type: Kotlin Module Group
title: Protocol
description: Canonical operation contracts are completed by an exact registry and projected into generated wire documents.
resource: file://protocol
tags: [kotlin, protocol, serialization]
timestamp: 2026-09-10T00:00:00Z
code_sources:
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
The CLI's App Server projection is version 9, and provider qualification requires
that version. Its successful read schemas carry mutually exclusive published and
live variants, including the corresponding source snapshot shape. The hosted
endpoint schema is version 2 and advertises the seven canonical read routes.
The final provider-qualified CLI schema document is 290,635 bytes, below the
unchanged 524,288-byte limit. Schema compatibility and native execution remain
separate evidence: the [acceptance review](../../docs/reviews/live-semantic-read-acceptance.md)
records the final default-route CLI matrix and actual provider invocation.
