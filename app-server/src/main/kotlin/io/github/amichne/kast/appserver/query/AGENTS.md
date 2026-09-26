# Public query boundary

Follow the root Engineering Dictum and `knowledge/contracts/public-tools.md`.
The authored schema owns parameter metadata and defaults; generated files are not
editable authorities. Run `:app-server:verifyPublicQueryGeneration` after schema
changes, and focused PublicTool contract/schema tests after admission changes.

A public request is intent, not compiler evidence. Admit the sole public query
grammar in `tools.schema.json` into `AdmittedPublicTool` through its private
construction boundary. Never reintroduce a raw JSON query payload, candidate
output, an INSPECT stage, magic wildcard, or implicit retry. The closed query
request admits exactly one of three actions: run with a source and ordered steps, resume
with a typed pipeline or retained-output execution continuation and optional grant, or read_result with a
retained-result reference and distinct presentation cursor. Keep exact-symbol
references, result references, execution continuations, and result cursors
separate in the admitted syntax. The public resume schema admits only issued
`query:v1` and `query-output:v1` token families; malformed and retained-result
tokens reject before canonical lowering.
The intent tools in `tools.schema.json` allow default controls to be omitted;
normalize omission or explicit null once through the generated defaults before canonical construction. Admit
those requests through `AdmittedPublicTool`, retaining tool identity and schema
identity in addition to the canonical operation. Invalid and empty are not synonyms for omitted. Preserve ordered stages and exact token bytes. Keep all workspace,
generation, semantic identity and completeness checks in their existing owners.
The `where` step carries the canonical closed visibility or primitive predicate directly;
the full public schema bounds primitive literals and rejects unknown fields and operators.
`concat` reuses the exact-reference and retained-result source DTOs. Set stages accept
only a retained-result right operand. Issued row IDs select retained rows without
reconstructing weaker exact references or treating a subset as complete coverage.
The `walk` stage admits bounded depth and strategy, then projects traversal records,
frontier progress, partial expansions, and coverage through the sole query operation.
The `bind` stage names a completed request-local stream for later stages. `join`
accepts an earlier binding or a retained result as its right input; an inner join
ends in typed binding rows with both exact symbols and their independent evidence.
Semi and anti joins continue as symbol streams. A retained anti join admits only
a complete right input; an incomplete named right input yields qualified output
without absence claims.

Encoding is a transport projection of retained typed syntax, not a reverse parser
for arbitrary canonical requests. No public constructor/copy may bypass admission.
Schema constraints and canonical syntax guards remain enforced even when a provider
supports a weaker generation schema. Passing a JSON Schema is not a capability.
