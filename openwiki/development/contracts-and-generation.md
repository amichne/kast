---
type: System Explanation
title: Contract ownership and generated projections
description: Traces canonical operations into public schemas, generated query syntax,
  typed wire output, and callable documentation.
tags:
  - kast
  - development
code_sources:
  - path: protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/CanonicalOperationDefinitions.kt
  - path: protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/CanonicalAgentToolDefinitions.kt
  - path: protocol/wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/OperationWireTable.kt
  - path: protocol/wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/presentation/CanonicalQueryCliDocuments.kt
  - path: build-logic/src/main/kotlin/kast.public-query-contract.gradle.kts
  - path: build-logic/src/main/kotlin/conventions/JsonContractVerification.kt
  - path: build-logic/src/main/kotlin/conventions/VerifyJsonContractsTask.kt
  - path: packaging/generate-public-query.py
  - path: cli/build.gradle.kts
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/bootstrap/InstalledServerProjectionDocuments.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/MintlifyCallableSchemaProjection.kt
  - path: config/json-contracts/README.md
  - path: protocol/contract/src/test/kotlin/io/github/amichne/kast/protocol/contract/QueryCompletionContractTest.kt
  - path: app-server/src/test/kotlin/io/github/amichne/kast/appserver/query/PublicToolSchemaTest.kt
  - path: build-logic/src/main/kotlin/conventions/jsoncontracts/JsonContractBaseline.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/bootstrap/HostedRejectionSchemas.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/projection/CliBoundaryDocuments.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/AdmittedReadRejections.kt
  - path: protocol/wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/metadata/HostedCapabilityDescriptor.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/rpc/QuerySessionRejectionDetail.kt
  - path: cli/src/test/js/tool-rpc-rejection.test.mjs
sources:
  - id: openwiki-source-9c15e352c1a80cb81e9b0fed
    resource: repo://build-logic/src/main/kotlin/conventions/JsonContractVerification.kt
  - id: openwiki-source-1fa0b28f94ec8950b78f5e48
    resource: repo://build-logic/src/main/kotlin/kast.public-query-contract.gradle.kts
  - id: openwiki-source-19c1ebaf58581a8fc0c5c10a
    resource: repo://cli/build.gradle.kts
  - id: openwiki-source-1d64f9c2cdc32afac226c9c0
    resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/bootstrap/InstalledServerProjectionDocuments.kt
  - id: openwiki-source-1e1d1563882058df6fa2e4da
    resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/MintlifyCallableSchemaProjection.kt
  - id: openwiki-source-cc6a899b4b43717488dde542
    resource: repo://config/json-contracts/README.md
generated: { by: "codex", at: "2026-10-15T01:04:37.434Z" }
verified:
  - by: openwiki/0.6.1
    at: 2026-10-31T09:13:30.169Z
---


# Contract ownership and generated projections

## One operation, several projections

The protocol contract defines operation identities, request documents, result variants, and finite failures.
`CanonicalOperationDefinitions` supplies authoritative definitions, grants, classifications, and schema inputs.
`CanonicalAgentToolDefinitions` projects tool descriptions from that registry.
`OperationWireTable` binds operations to their wire codecs.
Public display text does not replace the admitted operation identity.

The public query schema is an authored interface in App Server.
Its generator projects Kotlin syntax, defaults, public tool identities, and selected harness delivery sections.
Admission maps that typed syntax into the canonical operation.
The public schema and the internal operation graph keep separate owners and evidence roles.

## Typed output and generated documentation

Canonical serializers own the CLI query envelope.
`InstalledServerProjectionDocuments` derives installed schemas from those serializer contracts.
`MintlifyCallableSchemaProjection` preserves canonical assertions when it projects callable documentation.
The CLI Gradle tasks generate and compare the checked-in OpenAPI artifact.
Manual edits to that artifact bypass the generator and can disappear at the next projection.

For a public syntax change, change the authored schema and its generator together.
For a result-shape change, change the typed result owner and its schema-backed projection.
Keep required discriminators, encoded defaults, and finite failure variants explicit.
Absent, null, empty, and default values remain distinct when the external contract distinguishes them.

## Independent guards

`verifyPublicQueryGeneration` compares generated Kotlin and harness outputs against the current source inputs.
Its generator regression gate also examines illegal defaults, reference families, and tool bindings.
Kotlin compilation depends on generation parity, so stale syntax cannot pass through a successful compiler task.

`verifyJsonContracts` uses an isolated Kotlin PSI scanner and an exact baseline for legacy manual JSON expressions.
It rejects new, changed, duplicated, and stale manual expressions.
The syntax guard does not establish an encoded result's semantic correctness.
Encoded-shape assertions and independent schema checks supply that proof.

Run the owning projection task after a generator change.
Then examine the focused encoded-shape cases and the affected contract consumers.

## Focused tests

- [QueryCompletionContractTest.kt](../../protocol/contract/src/test/kotlin/io/github/amichne/kast/protocol/contract/QueryCompletionContractTest.kt)
- [PublicToolSchemaTest.kt](../../app-server/src/test/kotlin/io/github/amichne/kast/appserver/query/PublicToolSchemaTest.kt)

## Source owners

- [CanonicalOperationDefinitions.kt](../../protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/CanonicalOperationDefinitions.kt)
- [CanonicalAgentToolDefinitions.kt](../../protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/CanonicalAgentToolDefinitions.kt)
- [OperationWireTable.kt](../../protocol/wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/OperationWireTable.kt)
- [CanonicalQueryCliDocuments.kt](../../protocol/wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/presentation/CanonicalQueryCliDocuments.kt)
- [kast.public-query-contract.gradle.kts](../../build-logic/src/main/kotlin/kast.public-query-contract.gradle.kts)
- [JsonContractVerification.kt](../../build-logic/src/main/kotlin/conventions/JsonContractVerification.kt)
- [VerifyJsonContractsTask.kt](../../build-logic/src/main/kotlin/conventions/VerifyJsonContractsTask.kt)
- [generate-public-query.py](../../packaging/generate-public-query.py)
- [build.gradle.kts](../../cli/build.gradle.kts)
- [InstalledServerProjectionDocuments.kt](../../cli/src/main/kotlin/io/github/amichne/kast/cli/bootstrap/InstalledServerProjectionDocuments.kt)
- [MintlifyCallableSchemaProjection.kt](../../cli/src/main/kotlin/io/github/amichne/kast/cli/MintlifyCallableSchemaProjection.kt)
- [README.md](../../config/json-contracts/README.md)

## Read next

- [Public admission](../queries/public-admission.md)
- [Wire overview](../modules/protocol.md)
- [Build roles](build-and-architecture.md)

## Rejection and capability projections

`AdmittedReadRejections` preserves the admitted execution budget beside a finite read failure.
Wire capability metadata refines operation identities and intent sets before projection.
CLI bootstrap schema selection keeps canonical read rejection separate from hosted endpoint and transport rejection.
`CliBoundaryDocuments` serializes private process-ingress rejection with typed DTOs.
The JSON syntax baseline admits only exact, justified fingerprints and counts.
It supplies no wildcard or baseline-write operation.

## New query-feedback projections

The public-query generator projects typed authority-mismatch and callback-subject details into the adapter contracts.
The JavaScript presentation owner supplies received-evidence summaries without a second semantic outcome.
Tool RPC rejection fields keep their typed local session or canonical authority detail.
The focused rejection and presentation cases exercise those encoded projections.
