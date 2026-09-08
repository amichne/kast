# Public query contract

The agent supplies intent; Kast establishes semantic evidence. The public query
schema must not expose the canonical evaluator's candidate/refinement state machine.

## Authority and physical ownership

`app-server/.../query/query.schema.json` is the authored public parameter authority.
Its generated Kotlin syntax and defaults, native parameters, and strict-target
parameters are projections, not competing registries. `PublicQueryContract` uses
Networknt (the existing validator) and Kotlin serialization to admit public input.
It is shared by `KastProvider` and the CLI through the existing `:cli -> :app-server`
dependency. It depends on protocol contract values, not compiler/service adapters.

The private constructor of `AdmittedPublicQuery` captures normalized public syntax
and the lowered `QueryRunRequest`. Encoding reads the retained typed syntax; it does
not reverse-engineer a public request from the wider canonical request grammar.
Only the CLI's wire-preparation adapter unwraps the canonical request. Candidate
selection, workspace/generation admission and compiler identity remain with the
existing query/runtime modules. The provider's only effect remains invoking the
qualified executable. No new operation, effect, lifecycle prerequisite or service
locator is introduced.

## Normative principles

1. Validate against the exact schema identity, then parse into a stronger value.
   A `ValidatedJsonValue` from an unrelated schema is not query admission evidence.
   Preserve operation/schema binding until the subprocess serialization boundary.
2. Omission uses the declaring type’s concrete default. Explicit null and invalid
   values are rejected. Empty projection is meaningful; empty scope selectors and
   empty reference collections are rejected. Unknown fields fail before effects.
3. Defaults are expressed on the declaring types and at construction sites, generated
   from the schema. There is no external defaults registry. Do not copy default literals
   into providers, command handlers, help tables, or a second policy registry.
4. Use closed `type` unions and CAPS_CASE enum tags. Name reusable concepts once.
   Authoring annotations are not provider protocol keywords. Provider projection
   must not weaken server admission or claim schema proof is compiler proof.
5. Preserve ordered steps, exact token bytes, requested source restrictions,
   finite failures, and completeness evidence. No hidden fuzzy retry, silent
   scope widening, candidate promotion, output-mode repair, or automatic deduplication.
6. One query grammar serves CLI and hosted calls. Old public candidate/inspect
   syntax is rejected, not retained as a compatibility alias. Internal canonical
   wire requests remain intentional evaluator contracts, not a second CLI mode.
7. Every change to the public contract updates its examples and executable rejection
   tests. Generated artifacts are checked read-only before compilation and by CI.
   Passing structural checks is not evidence of live compiler or model behavior.

## Parameters and defaults

Minimal native request:

```json
{"type":"QUERY","from":{"type":"SEARCH","query":"OrderService"}}
```

`SEARCH`, `ALL`, and `REFS` are the only sources. `FILTER`, `EXPAND`, and `DISTINCT`
are the only steps. Search matching defaults to exact declaration-name matching,
not unique overload resolution. All matching overloads remain separate symbols.
No implicit fuzzy fallback is permitted.

The schema owns the exact default values. Currently, discovery includes supported
class/function/property/type-alias families in main and test sources; directory
and package restrictions default to `RECURSIVE`, including the named directory or
package and all nested directories or subpackages. Omitted steps mean no transformations.
Omitted selection includes name/location. `select: []` retains only mandatory result
identity/kind information. Constructor discovery is not newly advertised.

Source-set entries are exact Gradle names, including custom and multiplatform names.
The default remains `["main", "test"]`. Names match imported source-root ownership,
not production/test categories or directory spelling; unmatched names contribute
no declarations. Shared roots can belong to multiple named sets. The most specific
root owns a file, including when a nested root is excluded by readability policy.
Scope uses `type: "DIRECTORY"` or `type: "PACKAGE"` with one `value`, plus shared
`containment` and `sourceSets`. Omitted scope defaults to the workspace-relative
directory `"."`. A directory value is retained as `WorkspaceRelativePath`; a package
value is retained as `PublicQueryPackageName`. Their sealed scope variants exclude
simultaneous directory and package targets. The shared JSON envelope needs no
`anyOf`/`allOf`; its discriminator-specific value grammar is proven by typed parsing.
Source-set and target constraints intersect during discovery. They do
not filter later expansion destinations. Filtering before expansion and after
expansion have different meanings; the sequence is preserved, including repeated
stages. Work stays under the existing exhaustive/interactive execution policy.
Exhaustive intent is not an unbounded budget or a completeness guarantee.

`REFS` copies tokens from `items[].ref.token`; an `exact:v2:` prefix proves syntax
only. An expired or foreign-workspace reference can still be rejected by execution.
Outputs remain unchanged in this revision: Complete/Qualified/Rejected, per-item
failures, provenance, compiler evidence, and generation-bound reference checks are
not suppressed. A qualified empty result never proves that no matches exist.

Strict-target parameter projection requires declared controls and expresses
the concrete default values for controls they do not customize. The native CLI/Codex
grammar permits omission; explicit null is rejected in both profiles.
Both forms pass the same full server admission and normalize identically. No live
OpenAI acceptance or model-accuracy improvement follows from schema validation alone.

## Breaking change and retirement

Installed server projection 8 replaces projection 7's public query grammar. A
broker and executable with incompatible contracts fail qualification; restarting
or recreating sessions with the matched installed contract is required. Do not
silently resume a persisted catalog under the new grammar. Existing catalog digests
remain the authority for session compatibility.

`query.run` still lowers to the canonical wire request, which is retained because
it belongs to the evaluator and runtime modules. This is not a legacy public alias.
The revision does not change result schemas or the specialist read/write tools.

## Requirement-to-proof map

| Requirement | Owner | Executable proof |
| --- | --- | --- |
| Schema owns syntax/defaults/projections | schema + generator | `:app-server:verifyPublicQueryGeneration` |
| Closed shapes, valid examples, rejected legacy requests | `PublicQuerySchemaTest` | `:app-server:test --tests '*PublicQuerySchemaTest'` |
| Declaring defaults reject null and preserve empty projection, stage order, tokens | `PublicQueryContractTest` | `:app-server:test --tests '*PublicQueryContractTest'` |
| Provider retains typed admission and rejects schema drift | `KastPublicQueryProviderTest` | `:app-server:test --tests '*KastPublicQueryProviderTest'` |
| Installed advertisement matches the public CLI grammar | `InstalledServerProjectionTest` | `:cli:test --tests '*InstalledServerProjectionTest'` |
| Existing module/effect constraints | architecture policy | `verifyKastArchitecture` |
| Packaged product, protocol integration and regression checks | existing product gate | `productBuildGate` |

The task dependency graph is Gradle's actual graph: generation parity precedes
compilation; compilation precedes boundary tests; existing productBuildGate joins
subproject checks, architecture, and installed-product/host checks. Do not create
editable completion flags or duplicate the module graph. Use existing generated
module knowledge and exact-head CI/JUnit reports as evidence.

Focused RED examples: remove a discriminator; add an unknown field; request an
empty kinds set; send a candidate token to REFS; add INSPECT; change a default
without regenerating; validate under another schema before calling query admission.
Each must be rejected by the named test or generation gate. GREEN restores the
legal counterpart and runs that same gate, not an unrelated end-to-end benchmark.

Completion requires exact-head CI, installed acceptance, full-diff review and
requirement revalidation. Until those are observed, the PR is not a completed
verified delivery. This scoped contract change does not implement a new generic
receipt/progression engine; no additional delivery-program proof is implied.

## References

- Slopsentral API Contracts: `source/skills/manage-json-schemas/references/schema-policy.md`
- Slopsentral Engineering Baseline: `source/skills/semantic-ratchet/SKILL.md`
- Kast root `AGENTS.md`: refine never erase, explicit effects, finite failures.
