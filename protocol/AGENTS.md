<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-10-03 | hash: d217066e0d36 -->

# protocol

## Purpose

Defines canonical operation models, authoritative operation/tool registries, and serialized wire documents.

## Key Files

- [HostedContract.kt](contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/HostedContract.kt) - strict hosted-contract identities and compatibility policy.
- [HostedCompatibilityDocument.kt](contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/HostedCompatibilityDocument.kt) - live compatibility evidence and implementation provenance.
- [CanonicalHostedContract.kt](wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/CanonicalHostedContract.kt) - canonical hosted wire and operation identity derivation.

- [WorkspaceRefreshDocuments.kt](contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/WorkspaceRefreshDocuments.kt) - typed explicit workspace lifecycle control documents, separate from canonical reads.

- [SourceReadFailureDetails.kt](contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/SourceReadFailureDetails.kt) - disjoint request, reference, and internal source failure causes.
- [DiagnosticProgressDocument.kt](contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/DiagnosticProgressDocument.kt) - diagnostic inventory, cumulative coverage, and execution stage.


- [ReadRecoveryAction.kt](contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/ReadRecoveryAction.kt) - finite read-failure recovery directions derived from the canonical reason.
- [AdmittedReadRejections.kt](contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/AdmittedReadRejections.kt) - operation-owned admitted failures retain finite reasons and required execution reports.
- [RelationOmissionDocument.kt](contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/RelationOmissionDocument.kt) - canonical provider omission evidence.
- [TraversalPartialExpansionDocument.kt](contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/TraversalPartialExpansionDocument.kt) - typed partial-expansion projection.
- [CanonicalSourceReadAnchorDocument.kt](contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/CanonicalSourceReadAnchorDocument.kt) - disjoint inline and hosted-handle source anchor admission.

- [contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/CanonicalOperation.kt](contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/CanonicalOperation.kt) - canonical operation domain.
- [contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/CanonicalQueryOperationModels.kt](contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/CanonicalQueryOperationModels.kt) - query operation models.
- [contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryResultDocuments.kt](contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryResultDocuments.kt) - query result items, omissions, and finite item failures.
- [contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/CanonicalQueryBindingDocuments.kt](contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/CanonicalQueryBindingDocuments.kt) - join mode and binding cell documents.
- [contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryWalkDocuments.kt](contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryWalkDocuments.kt) - traversal record and progress projections within query results.
- [registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/CanonicalOperationDefinitions.kt](registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/CanonicalOperationDefinitions.kt) - authoritative operation catalog.
- [registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/OperationDefinitionFactory.kt](registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/OperationDefinitionFactory.kt) - shared typed definition construction and budgets.
- [registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/CanonicalAgentToolDefinitions.kt](registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/CanonicalAgentToolDefinitions.kt) - tool catalog projection.
- [wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/OperationWireTable.kt](wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/OperationWireTable.kt) - operation-to-wire binding table.
- [wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/WireEnvelope.kt](wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/WireEnvelope.kt) - wire envelope.
- [wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/QueryResultItemWireDocuments.kt](wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/QueryResultItemWireDocuments.kt) - query item serialization and admission.

- [registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/PublicToolIdentity.kt](registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/PublicToolIdentity.kt) - closed public tool identity vocabulary.

- [QueryQuestionDocument.kt](contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryQuestionDocument.kt) - original source, steps, and output retained with results.
- [QueryExpansionScopeDocument.kt](contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryExpansionScopeDocument.kt) - closed expansion policy independent of seed selection.
- [QueryRelationObservationDocument.kt](contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryRelationObservationDocument.kt) - requested and effective domains with finite coverage witnesses.
- [QueryImpactSourceDocument.kt](contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryImpactSourceDocument.kt) - producer, domain, flow, and reviewed model request documents.
- [ImpactAccountingDocument.kt](contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/ImpactAccountingDocument.kt) - conservation and unresolved obligation accounting.
- [ImpactWitnessDocuments.kt](contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/ImpactWitnessDocuments.kt) - retained producer, model, native-read, rejected-read, and compact finding presentation; finding links expand original path evidence.
- [CanonicalQueryCliDocuments.kt](wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/presentation/CanonicalQueryCliDocuments.kt) - typed CLI query envelopes and serializer owners for installed schemas.

## Subdirectories

- `contract` - transport-independent operation and compatibility models.
- `registry` - definitions, classification, blockers, budgets, and hosted projections.
- `wire` - documents, serializers, metadata, and canonical mappings.

## Entry Points

- Gradle projects: `:protocol:contract`, `:protocol:registry`, `:protocol:wire`.

## Navigation Hints

- Start with the [repository knowledge](../openwiki/modules/protocol.md) and follow its `code_sources` for source evidence. Root engineering rules remain authoritative; this map adds navigation only.

- For a public operation, read its contract, then registry definition, then wire serializer.
- For tool availability or budgets, begin in `registry`; for JSON compatibility, begin in `wire`.
- Hosted compatibility requires exact contract equality and existing platform admission; implementation versions remain provenance.
