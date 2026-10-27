# trace

Trace one previously selected exact declaration and its affected uses in the workspace under one shared grant.

```json
{"request":{"type":"RUN","source":{"type":"SYMBOL_REFS","symbolRefs":["<exact-ref-returned-by-Kast>"]},"steps":[],"terminalStep":{"type":"TRACE","expansionScope":{"type":"WORKSPACE"},"output":{"type":"SYMBOLS","fields":["NAME","LOCATION","SIGNATURE"]}},"retention":"RETAIN","executionBudget":{"maxElapsedMs":30000}}}
```

Accepted references: Issued exact symbols in SYMBOL_REFS.
Returned capabilities: Grouped symbols, connections, and occurrences.
Legal next actions: Preserve completion and omissions. Read retained-evidence.md.

Canonical choices and defaults:
- `Trace.type`: choices: ["TRACE"], default: "TRACE".
- `SymbolsOutput.type`: choices: ["SYMBOLS"], default: "SYMBOLS".
- `SymbolsOutput.fields`: choices: ["NAME","LOCATION","SIGNATURE","SOURCE"].

Supported outputs: SymbolsOutput, OccurrencesOutput, TraversalRecordsOutput, BindingRowsOutput, ValuePathsOutput, ImpactWitnessOutput. TRACE accepts SYMBOLS only.

Read `../query-schema.json` for complete shapes and constraints.
