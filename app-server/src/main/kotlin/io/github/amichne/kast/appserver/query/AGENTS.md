# Public query interface

Follow the root Engineering Dictum and `openwiki/contracts/public-tools.md`.
The authored schema owns parameter metadata and defaults; generated files are not
editable authorities. Run `:app-server:verifyPublicQueryGeneration` after schema
changes, and focused PublicTool contract/schema tests after admission changes.

A public request is intent, not compiler evidence. Admit the sole public query
grammar in `tools.schema.json` into `AdmittedPublicTool` through its private
construction boundary. Never reintroduce a raw JSON query payload, candidate
output, an INSPECT stage, magic wildcard, or implicit retry. The closed query
request admits exactly one of four actions:

- `RUN` accepts a source and optional ordered steps.
- `RESUME` accepts a typed retained-output continuation and an optional presentation budget.
- `READ_RESULT` accepts a retained-result reference and a distinct presentation cursor.
- `READ_SOURCE` accepts an issued candidate reference for its exact source anchor.

Source reads reuse the canonical source owner and preserve its authority and freshness failures.
Keep candidate references, exact-symbol
references, result references, execution continuations, and result cursors
separate in the admitted syntax. The public `RESUME` schema admits only the
`query-output:v1` token family. Internal `query:v1` continuations, malformed tokens, and
retained-result tokens reject before canonical lowering. `RESUME` only delivers already-produced output.
It cannot continue semantic execution or change its grant. Schema admission does not establish issued authority.
The intent tools in `tools.schema.json` allow default controls to be omitted;
normalize omission or explicit null once through the generated defaults before canonical construction. Admit
those requests through `AdmittedPublicTool`, retaining tool identity and schema
identity in addition to the canonical operation. Invalid and empty are not synonyms for omitted. Preserve ordered stages and exact token bytes. Keep all workspace,
generation, semantic identity and completeness checks in their existing owners.
The `WHERE` step lowers its typed visibility or primitive predicate into the canonical predicate.
The full public schema bounds primitive literals and rejects unknown fields and operators.
`CONCAT` reuses the exact-reference and retained-result source DTOs. Set stages accept
only a retained-result right operand. Issued row IDs select retained rows without
reconstructing weaker exact references or treating a subset as complete coverage.
The `WALK` stage admits exhaustive traversal or an explicit depth and strategy.
It projects traversal records, frontier progress, partial expansions, and coverage through the sole query operation.
`JOIN` accepts a retained symbol result as the right operand.
An inner join yields typed binding rows with both exact symbols and their independent evidence.
`PROJECT_BINDING` selects one named cell before later symbol stages.
Retained binding results can seed another run, including selected issued row IDs.
Semi and anti joins continue as symbol streams. An anti join admits only a complete right operand.

Encoding is a transport projection of retained typed syntax, not a reverse parser
for arbitrary canonical requests. No public constructor/copy may bypass admission.
Schema constraints and canonical syntax guards remain enforced even when a provider
supports a weaker generation schema. Passing a JSON Schema is not a capability.
