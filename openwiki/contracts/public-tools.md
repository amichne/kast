---
type: API Contract
title: Public intent tools
description: Public query, diagnostics, and bounded source changes lower into canonical
  operations without transferring compiler authority.
resource: file://app-server/src/main/resources/io/github/amichne/kast/appserver/query/tools.schema.json
tags:
- tools
- query
- protocol
- agents
code_sources:
- path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryTextMatchDocument.kt
  symbols:
  - QueryTextMatchDocument
- path: query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryTextMatches.kt
  symbols:
  - QueryTextMatches
- path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/WorkspaceLifecycleRequest.kt
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
  symbols:
  - PublicToolContract
  - AdmittedPublicTool
  - PublicToolCanonical
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/DaemonOperationProtocol.kt
  symbols:
  - DaemonOperationProtocol
  - DaemonOperationSelection
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/InstalledDaemonOperationClient.kt
  symbols:
  - DaemonOperationClient
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolMapping.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolExpansionScopeMapping.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolDiscoveryMapping.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolDiscoveryDocuments.kt
- path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/CanonicalQueryOperationModels.kt
- path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryResultDocuments.kt
- path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/CanonicalQueryStepModels.kt
- path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/CanonicalQueryBindingDocuments.kt
- path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryWalkDocuments.kt
- path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryResultReferences.kt
- path: query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryRetainedResult.kt
- path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryStateStore.kt
- path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryOutcomeProjection.kt
- path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/CanonicalQueryProtocol.kt
- path: protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/CanonicalAgentToolDefinitions.kt
- path: protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/PublicToolIdentity.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/provider/KastQueryInput.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/provider/KastProvider.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/protocol/codex/CodexSessionProjection.kt
- path: cli/src/main/kotlin/io/github/amichne/kast/cli/command/tool/PublicToolCommands.kt
- path: cli/src/main/kotlin/io/github/amichne/kast/cli/bootstrap/InstalledServerProjectionDocuments.kt
- path: cli/src/test/kotlin/io/github/amichne/kast/cli/CopilotInputSchemaCompatibilityTest.kt
- path: query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryWalkEvidence.kt
- path: cli/src/test/kotlin/io/github/amichne/kast/cli/LiveReadOutputSchemaTest.kt
- path: cli/src/main/kotlin/io/github/amichne/kast/cli/bootstrap/HostedRejectionSchemas.kt
- path: protocol/wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/presentation/QueryResultRowCliDocuments.kt
- path: protocol/wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/presentation/CanonicalQueryCliDocuments.kt
- path: app-server/src/test/kotlin/io/github/amichne/kast/appserver/query/PublicToolContractTest.kt
- path: app-server/src/test/kotlin/io/github/amichne/kast/appserver/query/PublicToolBindingContractTest.kt
- path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/HostedSymbolHandle.kt
- path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/CanonicalSourceReadAnchorDocument.kt
- path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/ImpactWitnessDocuments.kt
- path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/ImpactAccountingValidation.kt
- path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/ImpactFindingProjection.kt
- path: query/protocol/src/test/kotlin/io/github/amichne/kast/query/protocol/ImpactFindingPresentationTest.kt
- path: query/protocol/src/test/kotlin/io/github/amichne/kast/query/protocol/PendingImpactReadResultTest.kt
sources:
  - id: openwiki-source-468da36f81e497d3a91bd73f
    resource: repo://app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolContract.kt
  - id: openwiki-source-63060dfccafe31127ab3d22b
    resource: repo://app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolExpansionScopeMapping.kt
  - id: openwiki-source-b005241729c6acea7908126a
    resource: repo://app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolImpactDocuments.kt
  - id: openwiki-source-25b472ce8bd658b8f8006f96
    resource: repo://app-server/src/test/kotlin/io/github/amichne/kast/appserver/query/PublicToolExpansionScopeContractTest.kt
  - id: openwiki-source-c3a707e4531bdd548867dd23
    resource: repo://packaging/generate-public-query.py
  - id: openwiki-source-a21072bd22321038737c578c
    resource: repo://protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/ImpactAccountingDocument.kt
  - id: openwiki-source-368288aea315bf5b4628a899
    resource: repo://protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/ImpactAccountingValidation.kt
  - id: openwiki-source-aa500efdcfbb4a93d17c95ed
    resource: repo://protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/ImpactSiteAccountingDocuments.kt
  - id: openwiki-source-1363c35728458151b90e82c5
    resource: repo://protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryExpansionScopeDocument.kt
  - id: openwiki-source-25796dce45aaa5a543a07570
    resource: repo://query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryCompleteMembership.kt
  - id: openwiki-source-a184ae82a49703816522523e
    resource: repo://query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryRetainedResult.kt
  - id: openwiki-source-584136874e8bed46fcb3def2
    resource: repo://query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/ImpactWitnessProjection.kt
  - id: openwiki-source-4bf64022307aff9f9f531f9b
    resource: repo://query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/RetainedQueryPresentation.kt
  - id: openwiki-source-06ad73bc218ddc2ffa485553
    resource: repo://query/protocol/src/test/kotlin/io/github/amichne/kast/query/protocol/ImpactFindingPresentationTest.kt
  - id: openwiki-source-ff3a32a34def3fbe81d63b1c
    resource: repo://query/protocol/src/test/kotlin/io/github/amichne/kast/query/protocol/PendingImpactReadResultTest.kt
generated: { by: "codex", at: "2026-10-03T14:16:26.318Z" }
verified:
  - by: openwiki/0.6.1
    at: 2026-10-03T14:16:26.318Z
---

# Public intent tools

The authored tool bundle generates Kotlin request DTOs, executable normalization defaults, closed presentation identities, full admission schemas, Codex registration schemas and separate Responses strict registrations. Its namespace description is shared by the generated App Server registration and the live Codex session projection. Every supplied public tagged variant requires a `type` discriminator; fixed records have no invented discriminator. Variant values use `CAPS_CASE`. The strict projection requires optional object keys and permits null for nullable controls; root `verbose` remains a non-null boolean in every projection. Nullable DTO fields lower null and omission through the same generated defaults. Typed facade DTOs lower public output, predicate, and strategy variants into canonical types. The five published identities are `query_symbols`, `check_diagnostics`, `add_declaration`, and `replace_body`, plus hosted-only `workspace_lifecycle`; the direct and hosted paths use the same admitted request and exact identity for the shared tools.

`query_symbols` and `check_diagnostics` execute bounded canonical reads. The query tool takes one required `request` object with a closed `RUN`, `RESUME`, or `READ_RESULT` type. Run admits exact impact producers, declaration discovery, scoped indexed-word discovery into containing declarations, containing named declaration at workspace-relative file offset, exact-symbol references, or an immutable retained symbol-result reference with optional issued row IDs as its source, plus ordered steps, typed output, optional retention, and optional execution grant. Resume takes only an issued execution continuation and optional grant. Read-result takes a result reference, optional presentation cursor, output matching the retained row type, and optional grant; it does not execute query stages. Run output selects `SYMBOLS` with selected fields, `OCCURRENCES` with individual relation facts, or `TRAVERSAL_RECORDS` with depth-bearing facts, plus inner-join `BINDING_ROWS`; read-result output accepts symbols, compiler-confirmed occurrences, depth-bearing traversal records, binding rows, value paths, or impact witness sections according to the retained row type. Omitted or null output defaults to symbols with name and location, while an empty symbol field list remains distinct. A new run from a retained result can present its occurrence or traversal-record facts. Diagnostics lower to the path, semantic diagnostic limit, optional continuation and execution grant request. Nullable run controls normalize before canonical construction. Directory/package scopes carry `DIRECTORY` or `PACKAGE` tags, and duplicates and invalid lexical values reject.

Hosted tools pass admitted requests through the provider and shared workspace preparation owner. The daemon checks exact workspace identity before the existing-IDE operation. Complete, qualified, and rejected results retain their distinct documents. There is no semantic CLI operation RPC or direct-IDE fallback.

The pipeline preserves source meaning, step order, repeated steps and empty projections. Expansion can present related declarations or individual occurrence facts through the one query result. Occurrence rows preserve repeated call sites, exact endpoint references, use-site location and provenance. Structured result omissions keep the exact subject, relation kind and provider evidence beside known positive rows. Query items expose exact-symbol `ref` values. Exact-symbol references, retained-result references, execution continuations, and result presentation cursors are distinct typed values. No token spelling creates authority: runtime owners re-admit workspace, lifetime, epoch and compiler evidence.

Run can request `retention: "RETAIN"`. The produced semantic rows, failures, coverage and producer progress are captured immutably under one bounded query-state lifetime. Issued `row_id` handles identify rows within that result. A qualified retained symbol result may seed a later run, including when its row set is empty; its original omissions and qualification remain attached. Optional `row_ids` select rows in order; an empty list is valid and a proper subset retains incomplete-selection qualification. Row handles must belong to the supplied result. Result reuse rejects an unavailable or stale basis. Retention capacity failure is reported without erasing the run result. Read-result pages a retained result without semantic provider work, while `resume` restores pending execution from the original plan without resending it.

`where` admits a closed visibility or primitive predicate. Primitive predicates select compiler-grounded `name`, `kind`, or `file`, a finite comparison operator, and a nonblank literal bounded to 512 characters at public admission. The facade lowers a typed public predicate into the canonical predicate type. `concat` accepts the same exact-reference or retained-result input shapes used as query sources, preserves stream order and multiplicity, and revalidates exact references under the current authority and query budget. `intersect`, `union`, and `difference` require a retained result on the right and compare canonical semantic identity, not names or token spelling. `difference` requires complete right coverage and no right failures or producer progress before it can establish absence. `distinct_symbols` removes duplicates only at its explicit stage. `join` reads a retained symbol result on the right, keyed solely by canonical identity. Inner join emits one typed binding row per matching pair, preserving multiplicity and both cells' exact references, relation connections and proven occurrence facts. `project_binding` selects one named cell from binding rows before symbol stages resume. Semi and anti join keep the left symbol stream. Unknown projection names reject during admission. Anti join requires complete right membership and rejects incomplete right input. Joined binding rows retain and page under their own row type; they may seed later queries through typed projection, but cannot serve as a symbol join right input. A mismatched read-result output rejects. `walk` admits a relation, positive maximum depth, and optional breadth-first or bounded-fan-out strategy; it reuses the traversal domain operation under the query budget. Query walk observations retain expanded frontier, progress, strategy, partial expansions, and incomplete coverage, with no separate public traversal continuation.

`query_symbols.request.output.fields` accepts `SOURCE` in symbol mode for a run. It returns a committed source window for each exact symbol, extending five whole lines before and after the declaration and clipping at the file boundary. It is opt-in because each selected symbol incurs a source read within the one admitted query transaction. A failed or withheld window retains a finite per-item source cause and `SOURCE_INCOMPLETE` qualification; it never supplies guessed text. Read-result can present a retained source window only when that evidence was captured in the original run.

MCP `tools/list` and tool RPC `catalog` advertise the same generated input schema. Copilot registers the generated strict query union without merging alternatives into optional property bags. `CopilotInputSchemaCompatibilityTest` checks variant disjointness in the generated provider schema.

The installed projection derives its advertised hosted tools from the shared definitions. App Server qualifies the packaged hosted catalog directly; the CLI command projection omits `workspace_lifecycle` and both hosted mutations, `add_declaration` and `replace_body`. Private admitted requests retain presentation and schema identities, excluding cross-tool substitution. Old persisted catalogs reject the versioned cutover grammar. Declaration discovery, containing-declaration location discovery, and scoped indexed-word discovery resolve to exact rows inside query. File discovery has no public route. `add_declaration` and `replace_body` compose private planning, application, verification and recovery into one call. Body replacement admits one exact existing non-inline named function with a block body, preserves its signature and surrounding source, and rejects expression bodies, contracts, and unsupported targets.

`SEARCH_TEXT` admits one case-sensitive ASCII `word` bounded to 256 characters,
with the existing discovery scope and supported declaration kinds. Each supported
nearest containing owner enters the existing exact-refinement stage. Each
owner carries one verified lexical exemplar in `matches`, with `INDEXED_WORD`
type, canonical file, UTF-16 range, a context excerpt bounded to 512 characters,
context range, and one-based line. These matches preserve relevance separately
from compiler-confirmed `OCCURRENCES`. Unsupported nearest owners qualify
coverage. Phrases, qualified literals and regexes fail admission. Retained and
composed symbol rows preserve their owner-bound exemplars; relation destinations
do not inherit the starting owner's lexical evidence.

One exact row can retain at most 1000 distinct match exemplars through
composition. A merge beyond that capacity rejects with
`TEXT_MATCH_LIMIT_EXCEEDED`; it preserves the existing evidence and never turns
overflow into an internal serialization failure.

The rejection corpus, typed accepted-action fixtures, typed binding projection admission and path rejection, CLI wire parity and production provider routing are deterministic proofs. They cover retired step spellings, malformed predicate fields and literals, incompatible set operands, and malformed or duplicate row IDs. Codex schemas omit the Responses-only `strict` field and retain separate stronger admission constraints. These checks do not by themselves establish live API acceptance or improved model first-call accuracy.

See the [public search guide](../../docs/public/search.mdx) and [semantic query flow](../flows/semantic-query.md).

## Human-readable callable contracts

The [response walkthrough](../../docs/public/reference/responses.mdx) separates
transport completion, semantic outcome and coverage. The
[symbol guide](../../docs/public/reference/symbols.mdx) distinguishes candidate
and exact-symbol evidence; generated model pages carry the field-level contract.

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
display titles. The projection also publishes typed tool RPC reply and direct
MCP result schemas as named components. Synthetic callable paths remain
documentation routes, with no HTTP server or interactive playground advertised.

Query execution continuations remain distinct from domain relation and traversal
cursors held inside the bounded query checkpoint. The [query protocol](../modules/query-protocol.md)
retains the stronger continuation ownership checks after structural admission.

Public exact-reference syntax accepts hosted `exact:v5:` and `exact:v4:` handles alongside existing `v2` and `v3` tokens. Returned references remain opaque and must be passed back unchanged. The host resolves compact handles before validating authority. `replace_body` requires one exact symbol reference and returns a fresh reference only after verification.

The published agent policy delegates ordinary workspace preparation to the installed
daemon. Agents do not orchestrate opening, polling, enablement or repair commands
for semantic reads. Preparation blockers retain their cause and operation identity.
Installation authorization excludes cache invalidation, forced synchronization and
unrelated IDE restarts. Exact-plan mutation approvals remain separate.

Kast's Codex response retains the admitted CLI envelope in its final JSON text
item. Compact source reads with returned text prepend the unchanged source in a
separate text item; clients parse the final envelope once. Process completion and
the original complete/qualified/rejected semantic result remain distinct; a
canonical rejection sets tool success false. The desktop display projection
retains raw text and exposes the final Kast JSON envelope through the supported
`McpToolCallResult.structuredContent` field. The model-facing dynamic-tool response
has no such property in the locally generated Codex 0.154.0 schema. Malformed,
missing, and non-object final display results omit structured content rather than
manufacturing an empty object. A compact source result retains its unchanged
leading source text while its final canonical envelope supplies structured content.

`query_symbols` starts new execution only through `request.type: "RUN"`. A
`RESUME` action carries the unchanged opaque execution continuation without the
source or stages. A `READ_RESULT` action carries a separate result reference and
optional presentation cursor. Qualified query output retains its required closed qualification progress state; verbose output also derives the redundant `continuation` and `terminal_reason` fields from that state. The result payload separately reports retention outcome and an
optional next result cursor. Exact items expose the scalar `ref` capability;
canonical equality remains internal to set and distinct stages, and `symbol_id` has
no public accessor.
Query walk observations include progress, strategy and page-local partial expansions.
Query occurrence output separates exact returned facts from bounded provider
omission evidence, with measured or explicitly unmeasured page counts.

Query reference rejection schemas retain the finite lookup and reacquisition
reasons, including work/time exhaustion, missing retained locators, changed
compiler identity and incompatible authority. Unknown reasons remain invalid.
Fresh reads may perform bounded exact-handle reacquisition inside the invocation;
returned `reference_acquisitions` identifies the refreshed handles. When that
lookup cannot establish current identity, rejection retains its specific cause.
Continuations and source snapshots remain strict. See
[query protocol](../modules/query-protocol.md#automatic-acquisition-for-fresh-reads).

Installed source rejection schemas enumerate their canonical finite reasons. Query relation expansion and walk retain finite per-item failures and structured omissions. Wire decode and CLI projection preserve each reason; an unknown rejection string is incompatible with the installed tool envelope.

The query and source output schemas separately admit rejected outcomes with a required,
nonnull `execution_budget` report. The existing reason string or query rejection
object retains its shape. An omitted report describes an unadmitted rejection;
an explicit null does not satisfy either schema variant.

Source output derives cursor availability from required qualified progress.
`upstream` checkpoints resume the native source page owner; `retained_output`
checkpoints drain detached entities while preserving original upstream coverage.
Terminal qualifications retain a finite reason and all source limitations. These
states do not independently override canonical complete/qualified/rejected status.

Canonical rejected query and source tool documents require the derived
`next_action` alongside the unchanged finite failure and any admitted budget.
Unknown or absent actions fail the installed schema. The
[outcome contract](operation-outcomes.md) defines the closed recovery directions, including waiting for an active checkpoint;
action text does not authorize silent reference refresh or an automatic retry.

The version-4 server projection advertises occurrence output for compiler-confirmed references with explicit declaration, file-scoped, or unavailable ownership, and traversal-record output for bounded multi-step reachability. Required discovery/reference observations preserve coverage separately from rows; traversal partial expansions preserve detailed omissions and inherited omission meaning. Retired standalone names are rejected; catalog digest binding remains required before dispatch.

Workspace setup belongs to the agent catalog, separately from the user CLI surface.
The private invocation binding remains available to the harness, while root help
and public local-command metadata omit it. Ordinary semantic requests prepare
the exact workspace without a separate lifecycle call.

`workspace_lifecycle` is a hosted effectful tool with action-specific tagged inputs for inspect, open, present, sync, configure_sync, release, close, request_user_close and status. The configure action applies a task-success refresh rule only to an exact project target. Host selection comes from installed configuration; caller identity comes from the coordinator thread. The `EXACT_PROJECT_CLOSE` approval policy applies to the explicit user-close branch. Ordinary managed cleanup still enforces ownership and shared use.

The public-contract generator also owns the supported version constant in the
single-file Copilot and Pi adapters. Each adapter validates the entire catalog
before registering its first tool. Version rejection names the expected and
observed version and selected executable. This admission establishes catalog
compatibility, not live IDEA semantic readiness.

The four semantic tools, hosted `workspace_lifecycle`, and direct `health_check` accept root `verbose: true` for
execution detail. Omitted or false selects compact typed presentation after
execution, retaining results, qualifications, identities and recovery records.
Completed diagnostic progress, host UUID/epoch bookkeeping, empty query failure
lists, and the verified mutation's duplicated plan preview are omitted. The
application retains its receipt, actual diff and fresh reference. Presentation
never changes retained evidence, request lowering or canonical wire outcomes.

Lifecycle results retain their full state and ownership evidence in both modes;
its tool-only boolean never enters canonical lifecycle requests or close approvals.


An `IMPACT` source declares producer invocation anchors with exact enclosing and callable references, an expansion domain, `KOTLIN_FORWARD_V1` semantics, a declaration inventory, and explicit representation or boundary models. Native admission revalidates the exact sites and model positions under current authority before execution. `VALUE_PATHS` reports compiler transfers, reviewed model applications and terminal obligations without inferring representation from names.

Public relation and walk steps accept `expansionScope` through the same closed `ExpansionScope` union used by `IMPACT.domain`. `EXPAND_RELATION` defaults to `WORKSPACE`; `WALK` defaults to `RETAINED_SEED`. Omission and explicit null retain those defaults. `SOURCE_DOMAIN` selects source sets, directory containment, production/test policy and generated-source inclusion, and lowers to the existing canonical expansion owner independently of producer selection or later row filters.

`READ_RESULT` with `IMPACT_WITNESS` selects `PRODUCERS`, `MODELS`, `NATIVE_READS`, `READ_REJECTIONS`, `FINDINGS` or `SITE_ACCOUNTING` from the same retained investigation ledger. These pages use the existing result reference, presentation cursor and byte fitter; they do not start semantic providers. Original question, basis, requested domain, counts and unresolved closure remain attached even when a page or selection displays fewer paths. A reusable source recipe must reacquire current references after epoch movement; retained tokens do not become a recipe.

`FINDINGS` presents one compact row per original path, without grouping routes that happen to share a destination. Each finding requires its original path ordinal and issued path row ID. It retains the producer and destination, current representation alternatives, ordered model provenance, closed terminal outcome and boundary obligations. The original compiler steps, complete model payloads and history expand through `VALUE_PATHS` on the same result with that ordinal as the presentation cursor and `executionBudget.maxResults: 1`; the expanded row retains the same row ID. These summaries add no semantic authority. Missing identity context, inconsistent original counts or ordinals, duplicate page row IDs and empty present representation alternatives fail closed.

Before an impact ledger is finalized, a qualified, empty `EVIDENCE_ONLY` value-path snapshot can be retained alongside its unchanged upstream checkpoint. `READ_RESULT` returns that qualified empty page without executing semantic work or consuming the checkpoint. Impact witness output returns the finite `RESULT_FIELD_UNAVAILABLE` rejection until an investigation ledger exists. Resume uses the original checkpoint to continue unfinished routes.

An `IMPACT` source may supply up to 128 exact `requestedSites`; omitted or null public input lowers to an empty universe. Each selected site is revalidated natively under the admitted basis and retains its admission grant and observed work. The investigated response requires the original requested-site universe even when empty. `SITE_ACCOUNTING` reports one finite `REACHED`, `EXCLUDED` or `RELATIONSHIP_UNPROVEN` outcome per original target. Reached paths and exclusions link to original path ordinals and row IDs; an unmatched target preserves required unresolved relationship closure. Selection proves site identity, not a producer relationship. These pages use the existing retained store and presentation cursor without repeating native reads.
