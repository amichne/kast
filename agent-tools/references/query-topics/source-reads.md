# source-reads

Read the body of an issued exact declaration under its original authority.

```json
{"request":{"type":"READ_SOURCE","candidateRef":"exact:v5:AAAAAAAAAAAAAAAAAAAAAA","region":"CALLABLE_BODY"}}
```

Accepted references: Issued candidate, exact symbol, or source selector.
Returned capabilities: Source text and region identity.
Legal next actions: Preserve freshness failures. A window does not prove complete source.

Canonical choices and defaults:
- `ReadSourceAction.type`: choices: ["READ_SOURCE"], default: "READ_SOURCE".
- `ReadSourceAction.region`: choices: ["ANCHOR","CALLABLE_BODY","CLASS_BODY","FILE","ENCLOSING_DECLARATION","ENCLOSING_CALLABLE_BODY","ENCLOSING_CLASS_BODY",null], default: "ANCHOR".

Supported outputs: SymbolsOutput, OccurrencesOutput, TraversalRecordsOutput, BindingRowsOutput, ValuePathsOutput, ImpactWitnessOutput. TRACE accepts SYMBOLS only.

Read `../query-schema.json` for complete shapes and constraints.
