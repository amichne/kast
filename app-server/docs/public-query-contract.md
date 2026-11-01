# Public tool and query contracts

The agent supplies intent; Kast establishes semantic evidence. Each public tool has one schema-bound admission and executable route.

## Current intent surface

`tools.schema.json` is the authored authority for four intent tools.
`packaging/generate-public-query.py` generates their DTOs, defaults, identities, descriptions, and admission parameters.
It also generates Codex parameters and separate Responses strict registrations.
Edit the authored schema. Run the generator after authored changes converge.
The installed catalog governs the tools and schemas for the installed release.

| Tool | Canonical owner | CLI route | Loading |
|---|---|---|---|
| `check_diagnostics` | `diagnostic.check` | `tool check_diagnostics` | eager |
| `query_symbols` | `query.run` | `tool query_symbols` | eager |
| `add_declaration` | `change` | `tool add_declaration` | deferred |
| `replace_body` | `change` | `tool replace_body` | deferred |

### Query actions and terminal output

All routes read one JSON document from stdin. `query_symbols` takes one required `request` object with exactly one action:

| Action | Required input | Meaning |
|---|---|---|
| `RUN` | `source`, `terminalStep` | Run optional ordered transformation `steps`, then the selected `EMIT` or `TRACE` terminal. |
| `RESUME` | Issued `continuation` | Deliver already-produced output with an issued `query-output:v1` continuation and an optional presentation budget. No semantic stages run. |
| `READ_RESULT` | Issued `result` | Present immutable retained rows with an optional `cursor` and matching typed `output`. No query stages run. |
| `READ_SOURCE` | Issued `candidateRef` | Read the exact source anchor through the source owner and preserve its authority and freshness failures. |

A `RUN` source can select declarations, indexed text, a declaration at a file offset, exact-symbol references, or a retained result.
It can also select an impact investigation.
Exact case-sensitive name matching is the discovery default. Source scope restricts discovery only, and ordered steps stay ordered.
Omitted, null, or empty `steps` means no transformations. A retained-result source accepts optional issued `rowIds`.

Public contract 17 requires exactly one non-null `terminalStep` object tagged `EMIT` or `TRACE`.
`EMIT.output` selects `SYMBOLS`, `OCCURRENCES`, `TRAVERSAL_RECORDS`, `BINDING_ROWS`, `VALUE_PATHS`, or `IMPACT_WITNESS` for the admitted final row kind.
`TRACE.output` accepts only `SYMBOLS`. Its `expansionScope` belongs directly on the terminal and defaults to workspace expansion when omitted or null.
`TRACE` cannot appear in `steps`. RUN-level `output` and `traceOptions` reject.
Retention, execution mode, and execution budget remain on `RUN` and govern the whole query.
EMIT permits at most 1000 transformation steps. TRACE permits 999 before its canonical TRACE suffix.
The other three actions reject `terminalStep`.

`READ_RESULT` supports the same output vocabulary for the retained row kind.
Its `output` field stays on `READ_RESULT`.
Omitted or null terminal output or `READ_RESULT.output` selects `SYMBOLS` with `NAME` and `LOCATION`.
Explicit symbol output requires `fields`. An empty array requests identity and kind only.
Occurrence output preserves each relation fact and repeated use site. Traversal-record output preserves depth and its relation fact.

Use `READ_RESULT` with matching typed output to present retained occurrence or traversal-record rows.
Delivery does not rerun the query. Other omitted or null controls use the authored defaults.
For a result source, omitted or null `rowIds` selects all rows. An empty `rowIds` array selects no rows.
A selected result keeps its original qualification and omissions. A subset cannot prove coverage of excluded rows.

Example handles are illustrative. Schema admission proves syntax, not issued authority.
Use unchanged handles from preceding responses in the current workspace.

### Filters, sets, and joins

The `WHERE` step carries one structured predicate.
A `VISIBILITY` predicate has a nonempty unique `values` list drawn from compiler-established visibility states.
A `PRIMITIVE` predicate selects `NAME`, `KIND`, or `FILE` and one operator: `EQUALS`, `NOT_EQUALS`, `STARTS_WITH`, or `ENDS_WITH`.
Its literal must be nonblank and contain at most 512 characters.
The full schema rejects unknown variants, fields, operators, and malformed values before canonical lowering.
Typed lowering preserves the admitted field, operator, literal, and visibility values.

`CONCAT` accepts exact-reference or retained-result inputs and preserves order and multiplicity.
`INTERSECT`, `UNION`, and `DIFFERENCE` need a retained result as the right operand, optionally selected by its issued row IDs.
They compare canonical semantic identities and keep qualification. An incomplete right operand cannot establish absence for `DIFFERENCE`.
`DISTINCT_SYMBOLS` removes duplicate identities only where explicitly placed.

A retained result owns its row IDs.
These IDs cannot substitute for result references, exact-symbol references, execution continuations, or presentation cursors.

`JOIN` reads a retained symbol result as the right operand and compares canonical symbol identity.
An `INNER` join yields binding rows with two distinct column names.
Each pair keeps both exact references, relation evidence, occurrence evidence, and multiplicity.
`PROJECT_BINDING` selects one named cell and returns to symbol stages, including `DISTINCT_SYMBOLS` and `WALK`.

A retained binding result can seed a later run. Issued row IDs select pairs before projection.
The selected cell keeps its own evidence. The result keeps coverage, omissions, and failures.
Incompatible stages and unknown column names reject during admission.

`SEMI` and `ANTI` joins keep the left symbol stream and can feed later stages.
An incomplete right operand rejects `ANTI` join.
`READ_RESULT` presents retained binding rows with `BINDING_ROWS` output and preserves pairs and row IDs.
Binding rows cannot serve as the right operand of a symbol join. The public grammar has no alternate join key.

The [generated row compatibility table](../../agent-tools/references/query-row-compatibility.md) records the existing `QueryRowAdmission` transitions.
Terminal selection does not prove row compatibility. Impact witness output keeps its protocol route through value paths before presentation.

### Traversal and completeness

`WALK` takes a relation and defaults to exhaustive breadth-first traversal within the workspace expansion domain. Omitted or null `maximumDepth` selects the canonical `EXHAUSTIVE` extent. An explicit `maximumDepth` from 1 to 1000 selects `THROUGH_DEPTH` and remains subject to the hosted bounded-depth ceiling; its omitted expansion policy retains the seed domain. An explicit `expansionScope` is preserved in either case. Resource limits preserve the original extent and reject when requested completeness is unproven. Optional bounded-fan-out exploration preserves its qualifications. The stage composes the existing traversal domain operation under the query execution budget.

Query results keep depth-bearing records and walk observations with frontier, progress, strategy, partial expansions, and coverage.
Public RUN needs complete semantic execution under its admitted execution mode and grant.
Public RESUME only delivers already-produced output.
It cannot continue an unfinished walk or increase its semantic grant.

### Admission and public identities

`PublicToolContract` validates the full schema, decodes generated syntax, and performs pure lowering. `AdmittedPublicTool` has a private constructor and keeps tool identity, typed syntax, and a closed query, source, diagnostic, or change request. The provider and CLI share this admission boundary. Re-encoding uses retained typed syntax; a request admitted for one tool cannot be reused under another tool's schema merely because both own `query.run`.

Null controls are an intentional property of this surface. Unknown/mixed shapes, duplicate sets, invalid lexical paths and unsupported controls reject. Parameter corrections contain only bounded static rules and parameter names. Raw input values and source payloads are not copied into correction text. The stronger server constraints remain enforced when the Codex/Responses generation schema drops unsupported validation keywords. Codex registrations have no `strict` field; Responses registrations use `strict: true`. Offline schema validation does not establish external API acceptance or model accuracy.

Tool identity is separate from canonical operation identity. Catalogs require unique tool names and exact bindings for CLI-invokable tools; hosted-only `workspace_lifecycle` has no CLI route. Repeated operation IDs require consistent operation effect, approval, budget and output contract. The operation continues to own all execution policy. Source and approved change tools remain; query composes the relation and traversal domains, and candidate lookup/refinement stays opt-in.

Installed server and CLI invocation projections carry the current bindings. Old catalogs fail qualification and must be recreated with a matched executable/broker. Each new agent tool has exactly one supported schema. `kast.query` and `kast.diagnostic_check` are retired agent names. The former `kast query run` and `kast diagnostic check` CLI routes are retired; use their schema-bound `kast tool` routes above.

The query `OCCURRENCES` output presents individual relation facts after `EXPAND_RELATION`.
`TRAVERSAL_RECORDS` presents bounded multi-step reachability after `WALK`. Retired standalone routes are absent from the catalog and reject at dispatch.

### Result evidence and tests

Query binding rows carry two named cells and preserve each matching pair. Query symbol rows carry exact-symbol references. Occurrence rows carry an exact reference and a structured relation fact; traversal-record rows add depth. Result omissions retain the exact subject, relation kind, and provider omission evidence. Walk observations retain strategy, progress, partial expansions, and incomplete coverage. Separate candidate lookup may return candidate references; these are not query output. Exact-symbol references, retained-result references, execution continuations, and result presentation cursors have different owners and cannot substitute for each other.

Runtime restoration owns authenticity, workspace, lifetime, epoch and scope. Input schema acceptance proves syntax only. Complete/qualified/rejected outcomes, item failures, signatures, occurrence facts, and traversal evidence remain intact; a qualified empty result is not proof of absence.

Executable checks are `PublicToolContractTest`, `PublicToolSchemaTest`, `KastPublicQueryProviderTest`, `PublicToolCommandTest`, `InstalledServerProjectionTest`, `CanonicalAgentToolDefinitionsTest`, and `GeneratedCliProjectionTest`. Run `:app-server:verifyPublicQueryGeneration`, the relevant module checks, `verifyKastArchitecture`, and the existing native replay for native semantic evidence. The [search guide](../../docs/public/reference/search.mdx) contains current examples.

## Discovery measurements in agent output

Compact discovery observations omit `examined_work_units`, `inventory_files`, `reacquired_files`, `examined_leaves`, `inventory_nanoseconds`, `declaration_scan_nanoseconds`, and `projection_nanoseconds`.
Omission means that this presentation does not supply a measurement. It does not mean zero work.
Verbose output keeps the exact values. Compact output keeps universe, ordering, progress, stop causes, authority, and required collections.

Installed server projection version 19 carries the revised output schema.
A consumer that needs all seven measurements in compact output is incompatible with this projection.
The executable and broker must use the same projection version. The public request grammar and host-wire contract stay unchanged.

The hosted diagnostic owner receives one measurement offer after each native discovery page returns.
Diagnostic schema version 15 adds `kast_discovery_page` records and publication counts in the read receipt.
Each read can publish at most 16 page records, subject to the configured diagnostic limit.
Suppression and logger failure keep separate finite outcomes. Neither outcome changes the semantic page or proves zero work.

## Admission and evidence

`tools.schema.json` is the only authored public query input grammar. Its generated Kotlin DTOs, admission parameters and provider projections are checked by `:app-server:verifyPublicQueryGeneration`. `PublicToolContract` validates the selected tool schema, decodes typed syntax and lowers it to the canonical operation. The schema and tool identities remain attached to the admitted request, so a validated value for another tool cannot be substituted.

Omitted or explicit null controls normalize once through the generated defaults. Invalid and empty values retain their own meaning. Unknown fields, duplicate sets, unsupported stages and lexical errors reject before effects. The provider's generation projection may omit validation keywords, but server admission enforces the full schema.

Canonical query execution owns workspace, semantic basis, exact reference restoration, resource limits, coverage and compiler evidence. Schema admission grants no compiler authority. Ordered stages and exact token bytes survive lowering and encoding. Retained results are immutable, bounded, and bound to the semantic basis that produced them. Reusing a qualified result preserves its positive rows, incomplete coverage, and omissions; a qualified empty result cannot establish absence. A requested retention can fail for capacity without erasing the returned query result.

Public admission accepts only the `query-output:v1` retained-output token family with its bounded UUID syntax. It rejects internal `query:v1` execution continuations, malformed tokens and `result:v1` references before canonical lowering. The caller sends only issued output continuation bytes and an optional presentation budget. Output delivery preserves the original query, semantic basis and execution outcome; it never reruns semantic search or changes its grant. A result cursor addresses a presentation page inside one separately supplied retained result and never resumes semantic execution. Expired, evicted, mismatched and stale retained state rejects. Query set membership uses canonical semantic identity, not names or token-string equality.

## Verification

Run `:app-server:verifyPublicQueryGeneration`, focused `PublicToolContractTest`, `PublicToolSchemaTest`, `KastQueryInputTest` and provider tests after public admission changes. Run `verifyJsonContracts`, `verifyKastArchitecture`, `knowledgeImpact` and `verifyKnowledgeBase` for the repository-wide contracts. A generated-schema check proves projection parity; it does not prove native compiler behavior or external model acceptance.
