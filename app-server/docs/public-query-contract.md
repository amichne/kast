# Public tool and query contracts

The agent supplies intent; Kast establishes semantic evidence. Each public tool has one schema-bound admission and executable route.

## Current intent surface

`tools.schema.json` is the authored authority for two tools. The existing `packaging/generate-public-query.py` generates their boundary DTOs, defaults, public identities/descriptions, full admission parameters, Codex registration parameters and separate Responses strict registrations. Generated artifacts are never edited directly.

| Tool | Canonical owner | CLI route | Loading |
|---|---|---|---|
| `check_diagnostics` | `diagnostic.check` | `tool check_diagnostics` | eager |
| `query_symbols` | `query.run` | `tool query_symbols` | eager |

All routes read one JSON document from stdin. `query_symbols` takes one required `request` object with exactly one action:

| Action | Required input | Meaning |
|---|---|---|
| `run` | `source`, `steps` | Execute an ordered query with optional `output` and `retention: "retain"`. |
| `resume` | Issued `continuation` | Resume the retained execution with an optional new `execution_budget`; the plan is not resent. |
| `read_result` | Issued `result` | Present immutable retained symbol or binding rows with optional `cursor` and matching typed `output`; no query stages run. |

A run can start from declaration discovery, issued exact-symbol references, or a retained symbol or binding result reference with optional issued `row_ids`. Exact case-sensitive name matching is the discovery default. Source scope restricts discovery only, and ordered steps stay ordered. Run `output` selects `symbols`, `occurrences`, `traversal_records`, or `binding_rows` according to the admitted final row kind; `read_result` output accepts symbols or binding rows according to the retained row type. Omitted or null output selects symbols with name and location. Empty symbol fields request mandatory identity and kind only. Occurrence output preserves each relation fact and repeated use site. Traversal-record output preserves depth and its relation fact. To present occurrence or traversal-record rows from a retained result, run a new query with that result as its source. Other omitted or null controls use the authored defaults. For a result source, omitted or null `row_ids` selects all rows; `[]` selects no rows. A selected result carries its original qualification and omissions into the next query, and selecting a subset cannot prove coverage of excluded rows.

The `where` step carries one structured predicate. A `visibility` predicate has a nonempty unique `values` list drawn from compiler-established visibility states. A `primitive` predicate selects `name`, `kind`, or `file`, one of `equals`, `not_equals`, `starts_with`, or `ends_with`, and a nonblank literal of at most 512 characters. The full schema rejects unknown predicate variants, fields, operators, and malformed values before canonical lowering. The generated DTO uses the canonical predicate type directly, so the admitted field, operator, literal, and visibility proof stay intact.

`concat` accepts the same exact-reference or retained-result input shapes used as query sources and preserves order and multiplicity. `intersect`, `union`, and `difference` require a retained result on the right, optionally selected by its issued row IDs. They compare canonical semantic identities and retain qualification; an incomplete right result cannot establish absence for `difference`. `distinct_symbols` removes duplicate identities only where explicitly placed. Row IDs are owned by a retained result and cannot substitute for result references, exact-symbol references, execution continuations, or presentation cursors.

`join` reads a retained symbol result on the right and compares canonical symbol identity. Inner join yields binding rows with two distinct column names; each pair retains both exact references and established relation/occurrence evidence without collapsing multiplicity. `project_binding` selects exactly one named cell and returns to symbol stages, including `distinct_symbols` and `walk`. A retained binding result can seed a later run, with issued row IDs selecting pairs before projection. The selected cell retains its own evidence, and the result retains coverage, omissions, and failures. Stages incompatible with the current row kind and unknown column names reject during admission. Semi and anti join keep the left symbol stream and may feed later stages. An incomplete right input rejects anti join. Read-result pages retained binding rows with `binding_rows` output, preserving pairs and row IDs. Binding rows cannot serve as a symbol join right input. The public grammar has no alternate join key.

`walk` takes a relation, `maximum_depth` from 1 to 1000, and optional breadth-first or bounded-fan-out strategy. It lowers to the canonical query walk stage, which composes the existing traversal domain operation under the query execution budget. Query results carry depth-bearing records and walk observations with frontier, progress, strategy, partial expansions, and coverage. The query continuation owns unfinished execution; the walk stage issues no separate public traversal token.

`PublicToolContract` validates the full schema, decodes generated syntax, and performs pure lowering. `AdmittedPublicTool` has a private constructor and retains tool identity, typed syntax and a closed query/diagnostic canonical request. The provider and CLI share this admission boundary. Re-encoding uses retained typed syntax; a request admitted for one tool cannot be reused under another tool's schema merely because both own `query.run`.

Null controls are an intentional property of this surface. Unknown/mixed shapes, duplicate sets, invalid lexical paths and unsupported controls reject. Parameter corrections contain only bounded static rules and parameter names. Raw input values and source payloads are not copied into correction text. The stronger server constraints remain enforced when the Codex/Responses generation schema drops unsupported validation keywords. Codex registrations have no `strict` field; Responses registrations use `strict: true`. Offline schema validation does not establish external API acceptance or model accuracy.

Tool identity is separate from canonical operation identity. Catalogs require unique tool names and exact bindings for CLI-invokable tools; hosted-only `workspace_lifecycle` has no CLI route. Repeated operation IDs require consistent operation effect, approval, budget and output contract. The operation continues to own all execution policy. Source and approved change tools remain; query composes the relation and traversal domains, and candidate lookup/refinement stays opt-in.

Installed server and CLI invocation projections carry the current bindings. Old catalogs fail qualification and must be recreated with a matched executable/broker. Each new agent tool has exactly one supported schema. `kast.query` and `kast.diagnostic_check` are retired agent names. The former `kast query run` and `kast diagnostic check` CLI routes are retired; use their schema-bound `kast tool` routes above.

The query `occurrences` output presents individual relation facts after `expand_relation`; `traversal_records` presents bounded multi-step reachability after `walk`. Retired standalone routes are absent from the catalog and reject at dispatch.

Query binding rows carry two named cells and preserve each matching pair. Query symbol rows carry exact-symbol references. Occurrence rows carry an exact reference and a structured relation fact; traversal-record rows add depth. Result omissions retain the exact subject, relation kind, and provider omission evidence. Walk observations retain strategy, progress, partial expansions, and incomplete coverage. Separate candidate lookup may return candidate references; these are not query output. Exact-symbol references, retained-result references, execution continuations, and result presentation cursors have different owners and cannot substitute for each other. Runtime restoration owns authenticity, workspace, lifetime, epoch and scope. Input schema acceptance proves syntax only. Complete/qualified/rejected outcomes, item failures, signatures, occurrence facts, and traversal evidence remain intact; a qualified empty result is not proof of absence.

Executable checks are `PublicToolContractTest`, `PublicToolSchemaTest`, `KastPublicQueryProviderTest`, `PublicToolCommandTest`, `InstalledServerProjectionTest`, `CanonicalAgentToolDefinitionsTest`, and `GeneratedCliProjectionTest`. Run `:app-server:verifyPublicQueryGeneration`, the relevant module checks, `verifyKastArchitecture`, and the existing native replay for native semantic evidence. The [search guide](../../docs/public/search.mdx) contains current examples.

## Admission and evidence

`tools.schema.json` is the only authored public query input grammar. Its generated Kotlin DTOs, admission parameters and provider projections are checked by `:app-server:verifyPublicQueryGeneration`. `PublicToolContract` validates the selected tool schema, decodes typed syntax and lowers it to the canonical operation. The schema and tool identities remain attached to the admitted request, so a validated value for another tool cannot be substituted.

Omitted or explicit null controls normalize once through the generated defaults. Invalid and empty values retain their own meaning. Unknown fields, duplicate sets, unsupported stages and lexical errors reject before effects. The provider's generation projection may omit validation keywords, but server admission enforces the full schema.

Canonical query execution owns workspace, semantic basis, exact reference restoration, resource limits, coverage and compiler evidence. Schema admission grants no compiler authority. Ordered stages and exact token bytes survive lowering and encoding. Retained results are immutable, bounded, and bound to the semantic basis that produced them. Reusing a qualified result preserves its positive rows, incomplete coverage, and omissions; a qualified empty result cannot establish absence. A requested retention can fail for capacity without erasing the returned query result.

An execution continuation resumes the same bound query and basis. Public admission accepts only the `query:v1` pipeline and `query-output:v1` retained-output token families with their bounded UUID syntax. It rejects malformed tokens and `result:v1` references before canonical lowering. The caller sends only issued continuation bytes and an optional new grant. A result cursor addresses a presentation page inside one separately supplied retained result and never resumes semantic execution. Expired, evicted, mismatched and stale retained state rejects. Query set membership uses canonical semantic identity, not names or token-string equality.

## Verification

Run `:app-server:verifyPublicQueryGeneration`, focused `PublicToolContractTest`, `PublicToolSchemaTest`, `KastQueryInputTest` and provider tests after public admission changes. Run `verifyJsonContracts`, `verifyKastArchitecture`, `knowledgeImpact` and `verifyKnowledgeBase` for the repository-wide contracts. A generated-schema check proves projection parity; it does not prove native compiler behavior or external model acceptance.
