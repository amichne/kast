# Public tool and query contracts

The agent supplies intent; Kast establishes semantic evidence. Each public tool has one schema-bound admission and executable route.

## Current intent surface

`tools.schema.json` is the authored authority for two tools. The existing `packaging/generate-public-query.py` generates their boundary DTOs, defaults, public identities/descriptions, full admission parameters, Codex registration parameters and separate Responses strict registrations. Generated artifacts are never edited directly.

| Tool | Canonical owner | CLI route | Loading |
|---|---|---|---|
| `check_diagnostics` | `diagnostic.check` | `tool check_diagnostics` | eager |
| `query_symbols` | `query.run` | `tool query_symbols` | eager |

All routes read one JSON document from stdin. The optional nullable `continuation` is absent/null for a new pipeline and preserves exact opaque bytes for resume. Other required nullable controls normalize once into the authored defaults before constructing a canonical request. Exact case-sensitive matching is the default. `query_symbols` selects declaration families and output fields, preserving overload identities. Advanced source scopes restrict discovery only, ordered steps stay ordered, and an empty return-field list remains meaningful.

`PublicToolContract` validates the full schema, decodes generated syntax, and performs pure lowering. `AdmittedPublicTool` has a private constructor and retains tool identity, typed syntax and a closed query/diagnostic canonical request. The provider and CLI share this admission boundary. Re-encoding uses retained typed syntax; a request admitted for one tool cannot be reused under another tool's schema merely because both own `query.run`.

Null controls are an intentional property of this surface. Unknown/mixed shapes, duplicate sets, invalid lexical paths and unsupported controls reject. Parameter corrections contain only bounded static rules and parameter names. Raw input values and source payloads are not copied into correction text. The stronger server constraints remain enforced when the Codex/Responses generation schema drops unsupported validation keywords. Codex registrations have no `strict` field; Responses registrations use `strict: true`. Offline schema validation does not establish external API acceptance or model accuracy.

Tool identity is separate from canonical operation identity. Catalogs require unique tool names and exact bindings for CLI-invokable tools; hosted-only `workspace_lifecycle` has no CLI route. Repeated operation IDs require consistent operation effect, approval, budget and output contract. The operation continues to own all execution policy. No fake operations, second evaluator or lifecycle prerequisite are added. Source, relation, traversal and approved change tools remain; candidate lookup/refinement stays opt-in.

Installed server projection **15** and CLI invocation projection **4** carry the current bindings. Old catalogs fail qualification and must be recreated with a matched executable/broker. Each new agent tool has exactly one supported schema. `kast.query` and `kast.diagnostic_check` are retired agent names. The former `kast query run` and `kast diagnostic check` CLI routes are retired; use their schema-bound `kast tool` routes above.

The catalog advertises `read_relations` for individual `relation.read` occurrences and `traverse_relations` for bounded multi-step `traversal.run` reachability. Retired aliases reject at input admission.

Search and advanced query results supply one scalar `ref`, preserving the issued candidate or exact token verbatim. Runtime reference restoration still owns authenticity, workspace, lifetime, epoch and scope. Input schema acceptance proves syntax only. Complete/qualified/rejected outcomes, item failures, signatures and occurrence facts remain intact; a qualified empty result is not proof of absence.

Executable checks are `PublicToolContractTest`, `PublicToolSchemaTest`, `KastPublicQueryProviderTest`, `PublicToolCommandTest`, `InstalledServerProjectionTest`, `CanonicalAgentToolDefinitionsTest`, and `GeneratedCliProjectionTest`. Run `:app-server:verifyPublicQueryGeneration`, the relevant module checks, `verifyKastArchitecture`, and the existing native replay for native semantic evidence. The [search guide](../../docs/public/search.mdx) contains current examples.

## Admission and evidence

`tools.schema.json` is the only authored public query input grammar. Its generated Kotlin DTOs, admission parameters and provider projections are checked by `:app-server:verifyPublicQueryGeneration`. `PublicToolContract` validates the selected tool schema, decodes typed syntax and lowers it to the canonical operation. The schema and tool identities remain attached to the admitted request, so a validated value for another tool cannot be substituted.

Omitted or explicit null controls normalize once through the generated defaults. Invalid and empty values retain their own meaning. Unknown fields, duplicate sets, unsupported stages and lexical errors reject before effects. The provider's generation projection may omit validation keywords, but server admission enforces the full schema.

Canonical query execution owns workspace, semantic basis, exact reference restoration, resource limits, coverage and compiler evidence. Schema admission grants no compiler authority. Ordered stages and exact token bytes survive lowering and encoding. Query results retain a scalar opaque reference and finite item failures; a qualified empty result cannot establish absence.

A continuation resumes the same bound query and basis. It carries pending work and stage-local distinct history; reference-string equality does not replace canonical declaration identity. Expired, evicted, mismatched and stale state rejects. A nullable continuation is absent or null for a new request; non-null values preserve their issued bytes.

## Verification

Run `:app-server:verifyPublicQueryGeneration`, focused `PublicToolContractTest`, `PublicToolSchemaTest`, `KastQueryInputTest` and provider tests after public admission changes. Run `verifyJsonContracts`, `verifyKastArchitecture`, `knowledgeImpact` and `verifyKnowledgeBase` for the repository-wide contracts. A generated-schema check proves projection parity; it does not prove native compiler behavior or external model acceptance.
