# relations

Return individual compiler-confirmed references. Replace the illustrative exact-ref placeholder with an issued ref.

```json
{"request":{"type":"RUN","source":{"type":"SYMBOL_REFS","symbolRefs":["<exact-ref-returned-by-Kast>"]},"steps":[{"type":"EXPAND_RELATION","relation":"REFERENCES"}],"terminalStep":{"type":"EMIT","output":{"type":"OCCURRENCES"}},"executionBudget":{"maxResults":50}}}
```

Accepted references: Issued exact symbols in SYMBOL_REFS.
Returned capabilities: Occurrences and source selectors.
Legal next actions: Read selected source or retain the result.

Canonical choices and defaults:
- `ExpandRelation.type`: choices: ["EXPAND_RELATION"], default: "EXPAND_RELATION".
- `ExpandRelation.relation`: choices: ["REFERENCES","CALLERS","CALLEES","IMPLEMENTATIONS","INHERITORS","OVERRIDES","TYPE_USES"].
- `OccurrencesOutput.type`: choices: ["OCCURRENCES"], default: "OCCURRENCES".

Supported outputs: SymbolsOutput, OccurrencesOutput, TraversalRecordsOutput, BindingRowsOutput, ValuePathsOutput, ImpactWitnessOutput. TRACE accepts SYMBOLS only.

Read `../query-schema.json` for complete shapes and constraints.
