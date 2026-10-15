---
type: System Explanation
title: Public query admission and capabilities
description: Explains public schema intent, private typed admission, distinct issued
  capabilities, scope defaults, and canonical lowering.
tags:
- kast
- queries
code_sources:
- path: app-server/src/main/resources/io/github/amichne/kast/appserver/query/tools.schema.json
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolContract.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolMapping.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolRefinement.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolExpansionScopeMapping.kt
- path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryCompletionDocument.kt
- path: protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/PublicToolIdentity.kt
- path: packaging/generate-public-query.py
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/PublicQueryPackageName.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/schema/CanonicalJson.kt
- path: app-server/src/test/kotlin/io/github/amichne/kast/appserver/query/PublicToolContractTest.kt
- path: app-server/src/test/kotlin/io/github/amichne/kast/appserver/query/PublicToolContinuationTest.kt
- path: app-server/src/test/kotlin/io/github/amichne/kast/appserver/query/PublicToolCompletionPolicyTest.kt
verified:
  - by: openwiki/0.6.1
    at: 2026-10-15T00:33:05.932Z
sources:
  - id: openwiki-source-468da36f81e497d3a91bd73f
    resource: repo://app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolContract.kt
  - id: openwiki-source-6699d194831006a24f8c790c
    resource: repo://app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolMapping.kt
  - id: openwiki-source-2d91c3200889ebbf0f5aa2ba
    resource: repo://app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolRefinement.kt
  - id: openwiki-source-9577b7564213b6b964c79590
    resource: repo://app-server/src/main/resources/io/github/amichne/kast/appserver/query/tools.schema.json
  - id: openwiki-source-01b72c0031ed69c6870649b2
    resource: repo://protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryCompletionDocument.kt
generated: { by: "codex", at: "2026-10-15T00:33:05.932Z" }
---

# Public query admission and capabilities

## An intent document enters one admitted syntax

The authored `tools.schema.json` owns public parameter metadata, examples, and defaults.
The generator projects Kotlin syntax and public tool identities from that source.
`PublicToolContract` first admits the tool identity and schema, then refines the document into `AdmittedPublicTool`.
The admitted value keeps the exact public identity and canonical operation together.
No public constructor or copy operation can bypass that admission path.

Omitted or explicit-null default controls normalize once through the generated defaults where the contract permits them.
Invalid, empty, and unknown values remain different from omission.
Unknown fields, unsupported operators, invalid token families, and incompatible row transitions reject before canonical execution.
A generation provider's weaker schema does not weaken the production syntax guards.

## Four query actions have distinct capabilities

`RUN` admits a source and ordered steps for a new semantic question.
`RESUME` admits an issued `query-output:v1` continuation for output already produced.
`READ_RESULT` admits a retained result reference with its presentation cursor.
`READ_SOURCE` admits an issued candidate reference for its exact source anchor.

Exact references, candidate references, retained result references, row identifiers, output continuations, and result cursors remain separate types.
The public `RESUME` interface rejects internal execution continuations and retained-result tokens.
Schema admission proves syntax. The canonical owner still establishes issuance, workspace authority, freshness, and retained lifetime.
An invented token with valid punctuation does not grant access to a stored result.

## Scope and completion

Discovery scope selects the initial declarations.
Expansion scope selects native relationship destinations before enumeration.
`SOURCE_DOMAIN` limits those destinations at their native owner, rather than filtering a complete workspace scan afterward.
An ordinary row predicate does not reduce the native expansion work that preceded it.

Every public run uses `COMPLETE_ONLY` under `COMPILER_RESOLVED_STATIC_V1`.
`TRACE` has a fixed topology and accepts an expansion scope without arbitrary depth controls.
Its result does not claim runtime reachability or path feasibility.
Unproved completion preserves finite rejection and available recovery evidence.

Public admission tests establish the accepted and rejected documents.
Compiler identity and current workspace permission remain downstream semantic proofs.

## Focused tests

- [PublicToolContractTest.kt](../../app-server/src/test/kotlin/io/github/amichne/kast/appserver/query/PublicToolContractTest.kt)
- [PublicToolContinuationTest.kt](../../app-server/src/test/kotlin/io/github/amichne/kast/appserver/query/PublicToolContinuationTest.kt)
- [PublicToolCompletionPolicyTest.kt](../../app-server/src/test/kotlin/io/github/amichne/kast/appserver/query/PublicToolCompletionPolicyTest.kt)

## Source owners

- [tools.schema.json](../../app-server/src/main/resources/io/github/amichne/kast/appserver/query/tools.schema.json)
- [PublicToolContract.kt](../../app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolContract.kt)
- [PublicToolMapping.kt](../../app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolMapping.kt)
- [PublicToolRefinement.kt](../../app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolRefinement.kt)
- [PublicToolExpansionScopeMapping.kt](../../app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolExpansionScopeMapping.kt)
- [QueryCompletionDocument.kt](../../protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryCompletionDocument.kt)
- [PublicToolIdentity.kt](../../protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/PublicToolIdentity.kt)
- [generate-public-query.py](../../packaging/generate-public-query.py)
- [PublicQueryPackageName.kt](../../app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/PublicQueryPackageName.kt)
- [CanonicalJson.kt](../../app-server/src/main/kotlin/io/github/amichne/kast/appserver/schema/CanonicalJson.kt)

## Read next

- [Execution](execution-and-composition.md)
- [Completion](trace-and-completion.md)
- [Generated contracts](../development/contracts-and-generation.md)
