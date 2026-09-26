---
type: API Contract
title: Public intent tools
description: One declaration-query and one diagnostic presentation lower into canonical operations without transferring compiler authority.
resource: file://app-server/src/main/resources/io/github/amichne/kast/appserver/query/tools.schema.json
tags: [tools, query, protocol, agents]
timestamp: 2026-09-25T00:00:00Z
code_sources:
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
    symbols: [PublicToolContract, AdmittedPublicTool, PublicToolCanonical]
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/DaemonOperationProtocol.kt
    symbols: [DaemonOperationProtocol, DaemonOperationSelection]
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/InstalledDaemonOperationClient.kt
    symbols: [InstalledDaemonOperationClient]
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolMapping.kt
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
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/SourceReadToolInputSchema.kt
  - path: cli/src/test/kotlin/io/github/amichne/kast/cli/CopilotInputSchemaCompatibilityTest.kt
  - path: query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryWalkEvidence.kt
  - path: cli/src/test/kotlin/io/github/amichne/kast/cli/LiveReadOutputSchemaTest.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/bootstrap/HostedRejectionSchemas.kt
  - path: protocol/wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/presentation/CanonicalQueryCliDocuments.kt
  - path: app-server/src/test/kotlin/io/github/amichne/kast/appserver/query/PublicToolContractTest.kt
  - path: app-server/src/test/kotlin/io/github/amichne/kast/appserver/query/PublicToolBindingContractTest.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/HostedSymbolHandle.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/CanonicalSourceReadAnchorDocument.kt
---

# Public intent tools

The authored tool bundle generates Kotlin request DTOs, concrete normalization defaults, closed presentation identities, full admission schemas, Codex registration schemas and separate Responses strict registrations. The hosted `query_symbols` and `check_diagnostics` presentations lower to canonical query and diagnostic operations. The former CLI routes are retired; provider admission retains distinct schemas for these presentations.

`query_symbols` and `check_diagnostics` are eager. The query tool takes one required `request` object with a closed `run`, `resume`, or `read_result` action. Run admits discovery, exact-symbol references, or an immutable retained symbol-result reference with optional issued row IDs as its source, plus ordered steps, typed output, optional retention, and optional execution grant. Resume takes only an issued execution continuation and optional grant. Read-result takes a result reference, optional presentation cursor, output matching the retained row type, and optional grant; it does not execute query stages. Run output selects `symbols` with selected fields, `occurrences` with individual relation facts, or `traversal_records` with depth-bearing facts, plus terminal inner-join `binding_rows`; read-result output accepts symbols or binding rows according to the retained row type. Omitted or null output defaults to symbols with name and location, while an empty symbol field list remains distinct. A new run from a retained result can present its occurrence or traversal-record facts. Diagnostics lower to the path, semantic diagnostic limit, optional continuation and execution grant request. Nullable run controls normalize before canonical construction. Directory/package scope shapes are exclusive, and duplicates and invalid lexical values reject.

Hosted tools pass admitted requests through the provider and shared workspace preparation owner. The daemon checks exact workspace identity before the existing-IDE operation. Complete, qualified, and rejected results retain their distinct documents. There is no semantic CLI operation RPC or direct-IDE fallback.

The pipeline preserves source meaning, step order, repeated steps and empty projections. Expansion can present related declarations or individual occurrence facts through the one query result. Occurrence rows preserve repeated call sites, exact endpoint references, use-site location and provenance. Structured result omissions keep the exact subject, relation kind and provider evidence beside known positive rows. Query items expose exact-symbol `ref` values; query candidate output and its inspect stage have been removed. Separate symbol lookup still uses candidate references. Exact-symbol references, retained-result references, execution continuations, and result presentation cursors are distinct typed values. No token spelling creates authority: runtime owners re-admit workspace, lifetime, epoch and compiler evidence.

Run can request `retention: "retain"`. The produced semantic rows, failures, coverage and producer progress are captured immutably under one bounded query-state lifetime. Issued `row_id` handles identify rows within that result. A qualified retained symbol result may seed a later run, including when its row set is empty; its original omissions and qualification remain attached. Optional `row_ids` select rows in order; an empty list is valid and a proper subset retains incomplete-selection qualification. Row handles must belong to the supplied result. Result reuse rejects an unavailable or stale basis. Retention capacity failure is reported without erasing the run result. Read-result pages a retained result without semantic provider work, while `resume` restores pending execution from the original plan without resending it.

`where` admits a closed visibility or primitive predicate. Primitive predicates select compiler-grounded `name`, `kind`, or `file`, a finite comparison operator, and a nonblank literal bounded to 512 characters at public admission. The facade uses the canonical predicate type directly. `concat` accepts the same exact-reference or retained-result input shapes used as query sources, preserves stream order and multiplicity, and revalidates exact references under the current authority and query budget. `intersect`, `union`, and `difference` require a retained result on the right and compare canonical semantic identity, not names or token spelling. `difference` requires complete right coverage and no right failures or producer progress before it can establish absence. `distinct_symbols` removes duplicates only at its explicit stage. `bind` captures an earlier symbol stream under a bounded query-local name; `join` reads that name or a retained symbol result on the right, keyed solely by canonical identity. Inner join is terminal and emits one typed binding row per matching pair, preserving multiplicity and both cells' exact references, relation connections and proven occurrence facts. Semi and anti join keep the left symbol stream. Duplicate, unknown, and forward binding names reject before execution. Anti join requires complete right membership: incomplete retained input rejects, and incomplete named input records `JOIN_INPUT_INCOMPLETE` without asserting absence. Joined binding rows retain and page under their own row type; they cannot seed symbol stages or serve as a join right input, and a mismatched read-result output rejects. `walk` admits a relation, positive maximum depth, and optional breadth-first or bounded-fan-out strategy; it reuses the traversal domain operation under the query budget. Query walk observations retain expanded frontier, progress, strategy, partial expansions, and incomplete coverage, with no separate public traversal continuation.

`query_symbols.request.output.fields` accepts `source` in symbol mode for a run. It returns a committed source window for each exact symbol, extending five whole lines before and after the declaration and clipping at the file boundary. It is opt-in because each selected symbol incurs a source read within the one admitted query transaction. A failed or withheld window retains a finite per-item source cause and `SOURCE_INCOMPLETE` qualification; it never supplies guessed text. Read-result can present a retained source window only when that evidence was captured in the original run.

The installed `source_read` input projection distributes declaration visibility alternatives into its filter choices. This preserves the canonical request variants while keeping every advertised tool input within two composition levels for Copilot catalog loading. `CopilotInputSchemaCompatibilityTest` checks both the depth limit and acceptance parity for every source filter variant.

The installed projection retains the full canonical hosted tool inventory. App Server qualifies the packaged hosted catalog directly; the CLI invocation projection omits hosted-only `workspace_lifecycle`. Repeated canonical operation IDs are allowed only with consistent effect, approval, budget and output metadata. Private admitted requests retain their presentation and schema identities through transport encoding, excluding cross-tool substitution. Old persisted catalogs reject rather than silently accepting a new grammar. File or text lookup and candidate refinement require explicit selection. Native-qualified change tools are deferred defaults; `change_plan` has approval policy `NONE`, while apply and recovery retain
`EXPLICIT` exact-plan approval. The hosted planning schema admits only
`add-declaration`; unsupported canonical intents do not enter another runtime.

The rejection corpus, typed accepted-action fixtures, earlier-only binding admission, duplicate/path rejection, CLI wire parity and production provider routing are deterministic proofs. They cover retired step spellings, malformed predicate fields and literals, incompatible set operands, and malformed or duplicate row IDs. Codex schemas omit the Responses-only `strict` field and retain separate stronger admission constraints. These checks do not by themselves establish live API acceptance or improved model first-call accuracy.

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

Query execution continuations remain distinct from domain relation and traversal
cursors held inside the bounded query checkpoint. The [query protocol](../modules/query-protocol.md)
retains the stronger continuation ownership checks after structural admission.

Public exact-reference syntax accepts hosted `exact:v5:` and `exact:v4:` handles alongside legacy `v2` and `v3` tokens. Returned references remain opaque and must be passed back unchanged. The host resolves compact handles before validating authority; source anchors also accept the corresponding candidate handle family.

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

`query_symbols` starts new execution only through `request.action: "run"`. A
`resume` action carries the unchanged opaque execution continuation without the
source or stages. A `read_result` action carries a separate result reference and
optional presentation cursor. Qualified query output derives execution
`continuation` and `terminal_reason` from its required closed qualification
progress state. The result payload separately reports retention outcome and an
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
[outcome contract](operation-outcomes.md) defines the six recovery directions;
action text does not authorize silent reference refresh or an automatic retry.

The server projection advertises query occurrence output for individual relation facts and query traversal-record output for bounded multi-step reachability. Retired standalone names are rejected; catalog digest binding remains required before dispatch.

Workspace setup belongs to the agent catalog, separately from the user CLI surface.
The private invocation binding remains available to the harness, while root help
and public local-command metadata omit it and the convenience `workspace open`
command is absent. Tool guidance directs the agent through inspect, open and status
before semantic queries, retaining exact identities and finite blockers.

`workspace_lifecycle` is an eager canonical effectful tool with action-specific tagged inputs for inspect, open, present, sync, configure_sync, release, close, request_user_close and status. The configure action applies a task-success refresh rule only to an exact project target. Host selection comes from installed configuration; caller identity comes from the coordinator thread. The `EXACT_PROJECT_CLOSE` approval policy applies to the explicit user-close branch. Ordinary managed cleanup still enforces ownership and shared use, while source-change `EXPLICIT` approval is unchanged.
