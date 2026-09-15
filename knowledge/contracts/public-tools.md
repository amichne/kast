---
type: API Contract
title: Public intent tools
description: Schema-bound search and diagnostics presentations lower into existing canonical operations without transferring compiler authority.
resource: file://app-server/src/main/resources/io/github/amichne/kast/appserver/query/tools.schema.json
tags: [tools, query, protocol, agents]
timestamp: 2026-09-14T00:00:00Z
code_sources:
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/ReadRecoveryAction.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/SourceQualifiedProgressDocument.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/CanonicalReadRejectionSchemas.kt
  - path: cli/src/test/kotlin/io/github/amichne/kast/cli/ReadRejectionSchemaParityTest.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/bootstrap/MintlifyCallableReference.kt
  - path: cli/src/test/kotlin/io/github/amichne/kast/cli/MintlifyCallableReferenceTest.kt
  - path: docs/public/docs.json
  - path: app-server/src/main/resources/io/github/amichne/kast/appserver/query/tools.schema.json
  - path: packaging/generate-public-query.py
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolContract.kt
    symbols: [PublicToolContract, AdmittedPublicTool, PublicToolCanonical]
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolMapping.kt
  - path: protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/CanonicalAgentToolDefinitions.kt
  - path: protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/PublicToolIdentity.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/provider/KastQueryInput.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/provider/KastProvider.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/protocol/codex/CodexSessionProjection.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/command/tool/PublicToolCommands.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/bootstrap/InstalledServerProjectionDocuments.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/CanonicalReadOperationModels.kt
    symbols: [RelationContinuationDocument]
  - path: cli/src/test/kotlin/io/github/amichne/kast/cli/LiveReadOutputSchemaTest.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/bootstrap/HostedRejectionSchemas.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/projection/CanonicalQueryCliDocuments.kt
  - path: app-server/src/test/kotlin/io/github/amichne/kast/appserver/query/PublicToolContractTest.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/HostedSymbolHandle.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/CanonicalSourceReadAnchorDocument.kt
---

# Public intent tools

The authored tool bundle generates Kotlin request DTOs, concrete normalization defaults, closed presentation identities, full admission schemas, Codex registration schemas and separate Responses strict registrations. The same production generator retains the explicit legacy `query run` CLI grammar.

`search_classes`, `search_functions`, `search_declarations` and `check_diagnostics` are eager. `query_symbols` is deferred. Ordinary searches fix or admit declaration kinds and request names, locations and signatures; diagnostics lower to the path, semantic diagnostic limit, optional continuation and execution grant request. Required nullable controls normalize before canonical construction. Directory/package scope shapes are exclusive, and duplicates and invalid lexical values reject.

The advanced pipeline preserves source meaning, step order, repeated steps and empty projections. Expansion returns related declarations; occurrence-oriented relation facts remain the relation-read contract. Query items and per-item failures expose one scalar `ref`, preserving the issued candidate or exact token verbatim. Named output schemas `CandidateRef`, `ExactSymbolRef`, and query-scoped `ContinuationRef` describe these opaque representations. No token spelling creates authority: existing runtime owners re-admit workspace, lifetime, epoch and compiler evidence.

Installed projection 12 and CLI invocation version 3 join by tool name. Repeated canonical operation IDs are allowed only with consistent effect, approval, budget and output metadata. Private admitted requests retain their presentation and schema identities through transport encoding, excluding cross-tool substitution. Old persisted catalogs reject rather than silently accepting a new grammar. Source, relation and traversal defaults remain unchanged. Raw candidate lookup/refinement requires explicit selection. Native-qualified change tools are deferred defaults; `change_plan` has approval policy `NONE`, while apply and recovery retain
`EXPLICIT` exact-plan approval. The hosted planning schema admits only
`add-declaration`; unsupported canonical intents do not enter another runtime.

The 32-example corpus, typed lowering, duplicate/path rejection, CLI wire parity and production provider routing are deterministic proofs. Codex schemas omit the Responses-only `strict` field and retain separate stronger admission constraints. These checks do not by themselves establish live API acceptance or improved model first-call accuracy.

See the [public search guide](../../docs/public/search.mdx) and [semantic query flow](../flows/semantic-query.md).

## Human-readable callable contracts

The [response walkthrough](../../docs/public/reference/responses.mdx) separates
process completion, semantic outcome, payload, and coverage. The
[symbol guide](../../docs/public/reference/symbols.mdx) distinguishes candidate
and exact-symbol evidence and documents the closed signature variants, nullable
projections, and compiler-rendered type-string boundary.

`MintlifyCallableReference` derives the OpenAPI reference from installed bindings.
`HostedRejectionSchemas` retains each packaged rejection branch and its referenced
definitions. Installed output composition includes the transitive definitions,
including bounded selected-build module/root failure evidence, before documentation
projection; it rejects missing or colliding schema definitions.
It promotes document-local definitions into tool-qualified `components.schemas`
addresses and labels variants from their existing discriminants, marking outcomes
that require live evidence with a distinct label. Schema-model
pages reference those generated components; authored prose does not replace the
machine contract. The focused test compares resolved validation assertions with
every installed input and output schema, ignoring only definition placement and
display titles. Synthetic callable paths remain documentation routes, with no
HTTP server or interactive playground advertised. Full-width contract pages keep
the generated fields visible without synthesized response examples; invocation
commands render as Bash blocks in the page content.

`read_relations` advertises both published `v1` and live `v2` relation
continuations in its resume input and qualified output. Both schemas use
`RelationContinuationDocument.TOKEN_PATTERN`; the production codec preserves
the authority's version. The [query protocol](../modules/query-protocol.md)
retains the stronger continuation ownership checks after structural admission.

Public exact-reference syntax accepts hosted `exact:v5:` and `exact:v4:` handles alongside legacy `v2` and `v3` tokens. Returned references remain opaque and must be passed back unchanged. The host resolves compact handles before validating authority; source anchors also accept the corresponding candidate handle family.

The published agent policy permits opening or reopening the exact repository in
IDE tools when the user has already authorized it, followed by saved/indexed
readiness. Missing authorization or information may use session elicitation;
nonblocking elicitation allows independent work to continue, but an absent reply
never authorizes the dependent action. Opening permission does not extend to
cache invalidation, forced synchronization, topology preparation, or unrelated
IDE restarts. Exact-plan mutation approvals retain their separate requirements.

Kast's Codex response contains one JSON text item with the admitted CLI envelope,
so clients parse once without splitting a summary prefix. Process completion and
the original complete/qualified/rejected semantic result remain distinct; a
canonical rejection sets tool success false. The desktop display projection
retains raw text and exposes a single Kast JSON object through the supported
`McpToolCallResult.structuredContent` field. The model-facing dynamic-tool response
has no such property in the locally generated Codex 0.154.0 schema. Malformed,
mixed, and non-object display results omit structured content rather than
manufacturing an empty object.

`query_symbols` accepts optional nullable `continuation`; omission or null starts
a query. It preserves the opaque token through public lowering. Qualified query
output derives nullable `continuation` and `terminal_reason` from its required closed qualification progress state, while exact items
expose only the scalar `ref` capability. Canonical equality remains internal to
`distinct_symbols`; `symbol_id` has no public accessor.
Traversal output includes progress, strategy and page-local partial expansions.
Relation output separates exact returned-fact soundness from bounded provider
omission evidence, with measured or explicitly unmeasured page counts.

Query reference rejection schemas retain all seven canonical reasons, including
`stale-authority`, `incompatible-authority`, and `incompatible-reference-version`.
Unknown reason strings remain invalid. Reacquire reference authority explicitly;
a rejected reference is never refreshed by spelling or converted to success.

Installed source, relation, and traversal rejection schemas enumerate their canonical finite reasons. Wire decode and CLI projection preserve each reason; an unknown rejection string is incompatible with the installed tool envelope.

All four read output schemas separately admit rejected outcomes with a required,
nonnull `execution_budget` report. The existing reason string or query rejection
object retains its shape. An omitted report describes an unadmitted rejection;
an explicit null does not satisfy either schema variant.

Source output derives cursor availability from required qualified progress.
`upstream` checkpoints resume the native source page owner; `retained_output`
checkpoints drain detached entities while preserving original upstream coverage.
Terminal qualifications retain a finite reason and all source limitations. These
states do not independently override canonical complete/qualified/rejected status.

Canonical rejected query, source, relation and traversal tool documents require the derived
`next_action` alongside the unchanged finite failure and any admitted budget.
Unknown or absent actions fail the installed schema. The
[outcome contract](operation-outcomes.md) defines the six recovery directions;
action text does not authorize silent reference refresh or an automatic retry.

Server projection version 12 advertises `read_relations` and `traverse_relations`
for the unchanged `relation.read` and `traversal.run` operations. Canonical
registry definitions own the input-only `semantic_query` and `impact_analyze`
aliases. Configuration resolves aliases before rejecting duplicate identities;
provider qualification carries those aliases into the selected registration.
Provider registration rejects advertised-name and alias collisions, while catalog
publication includes only preferred names. Legacy inputs remain supported through
0.40.x, with removal no earlier than 0.41.0. Catalog digest binding remains
required before either input spelling dispatches.
