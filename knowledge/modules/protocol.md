---
type: Kotlin Module Group
title: Protocol
description: Canonical operation contracts are completed by an exact registry and projected into generated wire documents.
resource: file://protocol
tags: [kotlin, protocol, serialization]
timestamp: 2026-09-14T00:00:00Z
code_sources:
  - path: cli/src/test/kotlin/io/github/amichne/kast/cli/HostedFailureBudgetSchemaTest.kt
  - path: runtime/hosted/src/test/kotlin/io/github/amichne/kast/runtime/hosted/HostedEndpointEncodingFixtureTest.kt
  - path: workspace/intellij-read/src/test/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedFailureEncodingFixtureTest.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/BrokerOperationalLimits.kt
  - path: app-server/src/test/kotlin/io/github/amichne/kast/appserver/provider/KastSchemaOutputBudgetTest.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/ReadRecoveryAction.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolContract.kt
  - path: protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/CanonicalAgentToolDefinitions.kt
  - path: docs/reviews/live-semantic-read-acceptance.md
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/RelationCheckpointDocument.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/RelationReadQualification.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedRelationPreparedCoverage.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/CanonicalOperation.kt
    symbols: [CanonicalOperation]
  - path: protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/OperationRegistry.kt
    symbols: [OperationRegistry, OperationRegistryConstruction]
  - path: protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/CanonicalOperationDefinitions.kt
  - path: protocol/wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/OperationWireTable.kt
    symbols: [OperationWireTable]
  - path: protocol/wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/OperationWireBinding.kt
  - path: protocol/wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/WireEnvelope.kt
  - path: protocol/wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/WireResponseByteMinimum.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/CanonicalSourceReadOperationModels.kt
  - path: protocol/contract/src/main/resources/ide-hosted/hosted-endpoint.schema.json
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/bootstrap/InstalledServerProjectionDocuments.kt
  - path: cli/src/test/kotlin/io/github/amichne/kast/cli/InstalledServerProjectionTest.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/bootstrap/HostedRejectionSchemas.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/provider/KastProvider.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/provider/BrokerProcess.kt
  - path: query/protocol/build.gradle.kts
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/CanonicalChangeOperationModels.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedChangeFailure.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedResponse.kt
---

# Protocol

Protocol has three layers:

1. `protocol:contract` owns the closed public operation set and transport-independent request/outcome models.
2. `protocol:registry` requires exactly one fully typed definition for every canonical operation, with unique operation and schema identities.
3. `protocol:wire` requires exactly one serializer binding per canonical operation and projects canonical documents.

Unknown identities remain explicit unknown results; neither registry nor wire construction manufactures authority for them. See [operation registry](../contracts/operation-registry.md) for the exact completeness invariant.

Each wire binding derives a necessary response-byte minimum from its serialized
schema and operation identity. Hosted request admission rejects caller allowances
below that bound before semantic dispatch. Bodies, reports and continuations remain
subject to full envelope fitting; the identity bound does not promise they will fit.

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
The CLI's App Server projection is version 13, and provider qualification requires
that version. Its successful read schemas carry mutually exclusive published and
live variants, including the corresponding source snapshot shape. The hosted
endpoint schema is version 3 and advertises the seven canonical read routes plus
change planning, approval preparation, apply and recovery. Version-2 endpoint
descriptors reject. `change.apply.v3` distinguishes `Verified`,
`AppliedUnverified` and `RecoveryRequired`; only the first carries a receipt.
Qualified effects retain their finite reason and exact plan identity.
Hosted rejection schemas retain their transitive definitions when embedded in
installed output schemas, preserving bounded module/root evidence for selected-build
source-scope failures.
Installed output schemas reuse equal compiler-signature, receiver, source-range,
source/relation/traversal qualification, rejection-reason, and execution-budget
definitions through local references. Admitted failures in the four canonical
reads preserve a required report alongside their existing finite reason; wire
decoding distinguishes missing metadata from invalid or null reports. The complete CLI schema regression reserves
4,096 bytes below the 524,288-byte qualification cap, which counts both stdout and
stderr from the process. Hosted read admission now also has a closed
`CONFIGURATION_REJECTED` outcome; canonical semantic outcome schemas retain their existing identities. Schema compatibility and native execution remain
separate evidence: the [acceptance review](../../docs/reviews/live-semantic-read-acceptance.md)
records the final default-route CLI matrix and actual provider invocation.

The current [public tool contracts](../contracts/public-tools.md) distinguish presentation identity from canonical operation identity. Three ordinary searches and deferred `query_symbols` share `query.run`; `check_diagnostics` shares `diagnostic.check`. Private admission retains each tool's schema identity and typed syntax through its exact CLI binding. The `tool` command family uses the existing-IDE read path. Operation effects, budgets, reference authority and exhaustive outcomes remain with their existing owners.

The hosted endpoint rejection schema admits bounded, discriminated change-failure detail and rejects unknown causes or contradictory outer failure codes. Runtime encoded-shape tests validate each closed variant against this independently owned schema.

Relation qualifications distinguish an upstream continuation from retained detached
output. A retained checkpoint preserves whether upstream work is complete, resumable,
or terminal-incomplete; its only supported next action is draining the output.
The legacy continuation field is derived from the checkpoint token, and wire
decoding rejects disagreement between them. Omissions and their finite remediation
remain unchanged on every fitted page, including the terminal suffix.

`ReadRecoveryAction` derives the rejected read's direction exhaustively from the
canonical failure, including its admitted wrapper. Wire round trips preserve the
reason and budget, then CLI projection derives the same action. Installed schemas
reuse the generated action enum and equal finite-failure evidence definitions.
The provider qualification byte allowance and its reserved headroom remain owned
by the broker operational limits.
Schema qualification uses the broker's 1 MiB catalog-scale allowance, with
4,096 bytes reserved by the generated-output check for process diagnostics.
This internal policy is independent of semantic read-result allowances; schema
detail and finite enums remain intact. The process reader rejects combined
stdout/stderr above its allowance rather than truncating or weakening a schema.

Hosted endpoint and hosted read rejection projections preserve an optional execution report from an actually admitted grant. The report shape matches the canonical serializer descriptor; CLI admission also checks its semantic dimension and clamping rules. Failure causes and stages retain their existing finite meanings.

Hosted configuration rejections retain three closed detail shapes: an unknown parameter cause, an invalid-value cause with its parameter, or inconsistent bounds with their inner and outer parameters. The packaged schema binds parameter identities to the exact `ReadLimitParameter.environmentKey` set. Configuration details cannot appear under an unrelated failure code. Checked-in typed fixture documents are byte-compared with actual workspace and endpoint encoders, then validated against packaged and installed schemas; unknown causes, missing/null fields, unknown parameter keys and extra fields reject. Each producing test declares those shared fixture bytes as an input, so changes invalidate its cached result.

Symbol inspection preserves native failure, genuine index unavailability and unsupported declaration as distinct finite wire rejections. Hosted `AddDeclaration` planning failures retain all finite relation, traversal and diagnostic limitations in the hosted endpoint detail. The separate workspace-refresh control schema preserves typed lifecycle outcomes without adding a canonical semantic operation.
