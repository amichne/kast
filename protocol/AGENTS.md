<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-09-11 | hash: d217066e0d36 -->

# protocol

## Purpose

Defines canonical operation models, authoritative operation/tool registries, and serialized wire documents.

## Key Files

- [contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/CanonicalOperation.kt](contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/CanonicalOperation.kt) - canonical operation domain.
- [contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/CanonicalQueryOperationModels.kt](contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/CanonicalQueryOperationModels.kt) - query operation models.
- [registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/CanonicalOperationDefinitions.kt](registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/CanonicalOperationDefinitions.kt) - authoritative operation catalog.
- [registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/CanonicalAgentToolDefinitions.kt](registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/CanonicalAgentToolDefinitions.kt) - tool catalog projection.
- [wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/OperationWireTable.kt](wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/OperationWireTable.kt) - operation-to-wire binding table.
- [wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/WireEnvelope.kt](wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/WireEnvelope.kt) - wire envelope.

- [registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/PublicToolIdentity.kt](registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/PublicToolIdentity.kt) - closed public tool identity vocabulary.

## Subdirectories

- `contract` - transport-independent operation and compatibility models.
- `registry` - definitions, classification, blockers, budgets, and hosted projections.
- `wire` - documents, serializers, metadata, and canonical mappings.

## Entry Points

- Gradle projects: `:protocol:contract`, `:protocol:registry`, `:protocol:wire`.

## Navigation Hints

- Start with the [repository knowledge](../knowledge/modules/protocol.md) and follow its `code_sources` for source evidence. Root engineering rules remain authoritative; this map adds navigation only.

- For a public operation, read its contract, then registry definition, then wire serializer.
- For tool availability or budgets, begin in `registry`; for JSON compatibility, begin in `wire`.
