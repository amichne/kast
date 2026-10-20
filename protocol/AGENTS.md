---
type: Module Guide
title: "Kast protocol"
description: "Defines canonical operations, public tool identities, compatibility evidence, and serialized wire documents."
resource: "repo://protocol"
tags: [kast, navigation]
timestamp: "2026-10-20T04:41:08Z"
code_sources:
  - path: protocol/contract/build.gradle.kts
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/CanonicalOperation.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/CanonicalQueryOperationModels.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/HostedContract.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryCompletionDocument.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryResultDocuments.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryCallbackObservationWireDocuments.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryCallbackBodyBindingWireSerializer.kt
  - path: protocol/contract/src/test/kotlin/io/github/amichne/kast/protocol/contract/QueryCompletionContractTest.kt
  - path: protocol/registry/build.gradle.kts
  - path: protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/CanonicalOperationDefinitions.kt
  - path: protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/PublicToolIdentity.kt
  - path: protocol/wire/build.gradle.kts
  - path: protocol/wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/OperationWireTable.kt
  - path: protocol/wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/presentation/CanonicalQueryCliDocuments.kt
---

<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-10-14 | hash: d217066e0d36 -->

# protocol

## Purpose

Defines canonical operations, public tool identities, compatibility evidence, and serialized wire documents.

## Key Files

- [CanonicalOperation.kt](contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/CanonicalOperation.kt) - the canonical operation domain.
- [CanonicalQueryOperationModels.kt](contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/CanonicalQueryOperationModels.kt) - query requests and outputs.
- [QueryCompletionDocument.kt](contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryCompletionDocument.kt) - `COMPLETE_ONLY`, the compiler-static model, and finite completion failures.
- [QueryResultDocuments.kt](contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryResultDocuments.kt) - query rows, omissions, and item failures.
- [QueryCallbackObservationWireDocuments.kt](contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryCallbackObservationWireDocuments.kt) - shared callback serialization and pure conversions.
- [QueryCallbackBodyBindingWireSerializer.kt](contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryCallbackBodyBindingWireSerializer.kt) - bounded decoding of callback owners and call-frame proofs before domain admission.
- [HostedContract.kt](contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/HostedContract.kt) - hosted contract identities and compatibility policy.
- [CanonicalOperationDefinitions.kt](registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/CanonicalOperationDefinitions.kt) - the authoritative operation catalog and grants.
- [PublicToolIdentity.kt](registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/PublicToolIdentity.kt) - the closed public tool names.
- [OperationWireTable.kt](wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/OperationWireTable.kt) - operation-to-wire mappings.
- [CanonicalQueryCliDocuments.kt](wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/presentation/CanonicalQueryCliDocuments.kt) - serialization for CLI query envelopes.

## Subdirectories

- [contract/](contract/) - operation types, shared callback serialization, serialized documents, and schema assets.
- [registry/](registry/) - operation definitions, tool projections, and budgets.
- [wire/](wire/) - serialization, schema generation, and canonical mappings.

## Entry Points

- Gradle projects: `:protocol:contract`, `:protocol:registry`, and `:protocol:wire`.
- [QueryCompletionContractTest.kt](contract/src/test/kotlin/io/github/amichne/kast/protocol/contract/QueryCompletionContractTest.kt) - encoded completion fields and rejection cases.

## Navigation Hints

- For an operation, read its contract, registry definition, and wire mapping in that order.
- Keep shared callback serialization in `contract`. Keep reference issuance in `query` and transport envelopes in `wire`.
- For public query syntax, read the authored schema through the app-server query guide.
- Keep complete coverage distinct from semantic completion and from output presentation.
- Admit hosted compatibility through exact contract equality and platform admission. Component versions record provenance.
- Read the [root guide](../AGENTS.md) for engineering rules and optional OpenWiki retrieval.
