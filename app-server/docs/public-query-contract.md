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
| `run` | `source`, `steps`, `return_fields` | Execute an ordered query; optionally request `retention: "retain"`. |
| `resume` | Issued `continuation` | Resume the retained execution with an optional new `execution_budget`; the plan is not resent. |
| `read_result` | Issued `result`, `return_fields` | Present an immutable retained result from an optional `cursor`; no query stages run. |

A run can start from declaration discovery, issued exact-symbol references, or a retained result reference with optional issued `row_ids`. Exact case-sensitive name matching is the discovery default. Source scope restricts discovery only, ordered steps stay ordered, and an empty return-field list remains meaningful. Omitted or null controls use the authored defaults. For a result source, omitted or null `row_ids` selects all rows; `[]` selects no rows. A selected result carries its original qualification and omissions into the next query, and selecting a subset cannot prove coverage of excluded rows.

The `where` step carries one structured predicate. A `visibility` predicate has a nonempty unique `values` list drawn from compiler-established visibility states. A `primitive` predicate selects `name`, `kind`, or `file`, one of `equals`, `not_equals`, `starts_with`, or `ends_with`, and a nonblank literal of at most 512 characters. The full schema rejects unknown predicate variants, fields, operators, and malformed values before canonical lowering. The generated DTO uses the canonical predicate type directly, so the admitted field, operator, literal, and visibility proof stay intact.

`concat` accepts the same exact-reference or retained-result input shapes used as query sources and preserves order and multiplicity. `intersect`, `union`, and `difference` require a retained result on the right, optionally selected by its issued row IDs. They compare canonical semantic identities and retain qualification; an incomplete right result cannot establish absence for `difference`. `distinct_symbols` removes duplicate identities only where explicitly placed. Row IDs are owned by a retained result and cannot substitute for result references, exact-symbol references, execution continuations, or presentation cursors.

`PublicToolContract` validates the full schema, decodes generated syntax, and performs pure lowering. `AdmittedPublicTool` has a private constructor and retains tool identity, typed syntax and a closed query/diagnostic canonical request. The provider and CLI share this admission boundary. Re-encoding uses retained typed syntax; a request admitted for one tool cannot be reused under another tool's schema merely because both own `query.run`.

Null controls are an intentional property of this surface. Unknown/mixed shapes, duplicate sets, invalid lexical paths and unsupported controls reject. Parameter corrections contain only bounded static rules and parameter names. Raw input values and source payloads are not copied into correction text. The stronger server constraints remain enforced when the Codex/Responses generation schema drops unsupported validation keywords. Codex registrations have no `strict` field; Responses registrations use `strict: true`. Offline schema validation does not establish external API acceptance or model accuracy.

Tool identity is separate from canonical operation identity. Catalogs require unique tool names and exact bindings for CLI-invokable tools; hosted-only `workspace_lifecycle` has no CLI route. Repeated operation IDs require consistent operation effect, approval, budget and output contract. The operation continues to own all execution policy. No fake operations, second evaluator or lifecycle prerequisite are added. Source, relation, traversal and approved change tools remain; candidate lookup/refinement stays opt-in.

Installed server projection **15** and CLI invocation projection **4** carry the current bindings. Old catalogs fail qualification and must be recreated with a matched executable/broker. Each new agent tool has exactly one supported schema. `kast.query` and `kast.diagnostic_check` are retired agent names. The former `kast query run` and `kast diagnostic check` CLI routes are retired; use their schema-bound `kast tool` routes above.

The catalog advertises `read_relations` for individual `relation.read` occurrences and `traverse_relations` for bounded multi-step `traversal.run` reachability. Retired aliases reject at input admission.

Query symbol rows carry exact-symbol references. Separate candidate lookup may return candidate references; these are not query output. Exact-symbol references, retained-result references, execution continuations, and result presentation cursors have different owners and cannot substitute for each other. Runtime restoration owns authenticity, workspace, lifetime, epoch and scope. Input schema acceptance proves syntax only. Complete/qualified/rejected outcomes, item failures, signatures and occurrence facts remain intact; a qualified empty result is not proof of absence.

Executable checks are `PublicToolContractTest`, `PublicToolSchemaTest`, `KastPublicQueryProviderTest`, `PublicToolCommandTest`, `InstalledServerProjectionTest`, `CanonicalAgentToolDefinitionsTest`, and `GeneratedCliProjectionTest`. Run `:app-server:verifyPublicQueryGeneration`, the relevant module checks, `verifyKastArchitecture`, and the existing native replay for native semantic evidence. The [search guide](../../docs/public/search.mdx) contains current examples.

## Admission and evidence

`tools.schema.json` is the only authored public query input grammar. Its generated Kotlin DTOs, admission parameters and provider projections are checked by `:app-server:verifyPublicQueryGeneration`. `PublicToolContract` validates the selected tool schema, decodes typed syntax and lowers it to the canonical operation. The schema and tool identities remain attached to the admitted request, so a validated value for another tool cannot be substituted.

Omitted or explicit null controls normalize once through the generated defaults. Invalid and empty values retain their own meaning. Unknown fields, duplicate sets, unsupported stages and lexical errors reject before effects. The provider's generation projection may omit validation keywords, but server admission enforces the full schema.

Canonical query execution owns workspace, semantic basis, exact reference restoration, resource limits, coverage and compiler evidence. Schema admission grants no compiler authority. Ordered stages and exact token bytes survive lowering and encoding. Retained results are immutable, bounded, and bound to the semantic basis that produced them. Reusing a qualified result preserves its positive rows, incomplete coverage, and omissions; a qualified empty result cannot establish absence. A requested retention can fail for capacity without erasing the returned query result.

An execution continuation resumes the same bound query and basis. Public admission accepts only the `query:v1` pipeline and `query-output:v1` retained-output token families with their bounded UUID syntax. It rejects malformed tokens and `result:v1` references before canonical lowering. The caller sends only issued continuation bytes and an optional new grant. A result cursor addresses a presentation page inside one separately supplied retained result and never resumes semantic execution. Expired, evicted, mismatched and stale retained state rejects. Query set membership uses canonical semantic identity, not names or token-string equality.

## Verification

Run `:app-server:verifyPublicQueryGeneration`, focused `PublicToolContractTest`, `PublicToolSchemaTest`, `KastQueryInputTest` and provider tests after public admission changes. Run `verifyJsonContracts`, `verifyKastArchitecture`, `knowledgeImpact` and `verifyKnowledgeBase` for the repository-wide contracts. A generated-schema check proves projection parity; it does not prove native compiler behavior or external model acceptance.
