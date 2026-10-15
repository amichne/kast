---
type: Module Guide
title: "Kast protocol"
description: "Defines canonical operations, public tool identities, compatibility evidence, and serialized wire documents."
resource: "repo://protocol"
tags: [kast, navigation]
timestamp: "2026-10-14T23:25:10Z"
source_revision: e014a489bfd7e69cbf2c4b61140ee771d857a03e
code_sources:
  - path: protocol/contract/build.gradle.kts
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/CanonicalOperation.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/CanonicalQueryOperationModels.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/HostedContract.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryCompletionDocument.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryResultDocuments.kt
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
- [HostedContract.kt](contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/HostedContract.kt) - hosted contract identities and compatibility policy.
- [CanonicalOperationDefinitions.kt](registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/CanonicalOperationDefinitions.kt) - the authoritative operation catalog and grants.
- [PublicToolIdentity.kt](registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/PublicToolIdentity.kt) - the closed public tool names.
- [OperationWireTable.kt](wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/OperationWireTable.kt) - operation-to-wire mappings.
- [CanonicalQueryCliDocuments.kt](wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/presentation/CanonicalQueryCliDocuments.kt) - serializers for CLI query envelopes.

## Subdirectories

- [contract/](contract/) - operation types, serialized documents, and schema assets.
- [registry/](registry/) - operation definitions, tool projections, and budgets.
- [wire/](wire/) - serializers, schema generation, and canonical mappings.

## Entry Points

- Gradle projects: `:protocol:contract`, `:protocol:registry`, and `:protocol:wire`.
- [QueryCompletionContractTest.kt](contract/src/test/kotlin/io/github/amichne/kast/protocol/contract/QueryCompletionContractTest.kt) - encoded completion fields and rejection cases.

## Navigation Hints

- For an operation, read its contract, registry definition, and wire mapping in that order.
- For public query syntax, read the authored schema through the app-server query guide.
- Keep complete coverage distinct from semantic completion and from output presentation.
- Admit hosted compatibility through exact contract equality and platform admission. Component versions record provenance.
- Read the [root guide](../AGENTS.md) for engineering rules and optional OpenWiki retrieval.
