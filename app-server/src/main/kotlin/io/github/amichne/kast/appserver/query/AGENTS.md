# Public query boundary

Follow the root Engineering Dictum and `knowledge/contracts/public-tools.md`.
The authored schema owns parameter metadata and defaults; generated files are not
editable authorities. Run `:app-server:verifyPublicQueryGeneration` after schema
changes, and focused PublicTool contract/schema tests after admission changes.

A public request is intent, not compiler evidence. Admit the sole public query
grammar in `tools.schema.json` into `AdmittedPublicTool` through its private
construction boundary. Never reintroduce a raw JSON query payload, candidate
output, an INSPECT stage, magic wildcard, or implicit retry. Pagination
continuation is optional nullable context: absent or null starts a query, while
a value must retain exact opaque bytes.
The intent tools in `tools.schema.json` allow default controls to be omitted;
normalize omission or explicit null once through the generated defaults before canonical construction. Admit
those requests through `AdmittedPublicTool`, retaining tool identity and schema
identity in addition to the canonical operation. Invalid and empty are not synonyms for omitted. Preserve ordered stages and exact token bytes. Keep all workspace,
generation, semantic identity and completeness checks in their existing owners.

Encoding is a transport projection of retained typed syntax, not a reverse parser
for arbitrary canonical requests. No public constructor/copy may bypass admission.
Schema constraints and canonical syntax guards remain enforced even when a provider
supports a weaker generation schema. Passing a JSON Schema is not a capability.
