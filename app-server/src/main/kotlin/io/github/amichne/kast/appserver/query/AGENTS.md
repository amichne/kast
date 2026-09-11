# Public query boundary

Follow the root Engineering Dictum and `app-server/docs/public-query-contract.md`.
The authored schema owns parameter metadata and defaults; generated files are not
editable authorities. Run `:app-server:verifyPublicQueryGeneration` after schema
changes, and focused PublicQuery contract/schema tests after admission changes.

A public request is intent, not compiler evidence. Admit into `AdmittedPublicQuery`
through its private-construction boundary; never reintroduce a raw JSON query
payload, candidate output, an INSPECT stage, magic wildcard, or implicit retry.
The legacy `query run` grammar keeps declaring defaults and rejects explicit null.
The intent tools in `tools.schema.json` require controls to be present; normalize
null once through the generated defaults before canonical construction. Admit
those requests through `AdmittedPublicTool`, retaining tool identity and schema
identity in addition to the canonical operation. Invalid and empty are not synonyms for omitted. Preserve ordered stages and exact token bytes. Keep all workspace,
generation, semantic identity and completeness checks in their existing owners.

Encoding is a transport projection of retained typed syntax, not a reverse parser
for arbitrary canonical requests. No public constructor/copy may bypass admission.
Schema constraints and canonical syntax guards remain enforced even when a provider
supports a weaker generation schema. Passing a JSON Schema is not a capability.
