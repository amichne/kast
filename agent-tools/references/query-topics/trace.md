# trace

Copy an issued exact reference instead of the non-issued example token. Trace affected uses with original completion and qualifications.

```json
{"request":{"type":"RUN","source":{"type":"SYMBOL_REFS","symbolRefs":["exact:v5:NON_ISSUED_EXAMPLE_ONLY"]},"steps":[],"terminalStep":{"type":"TRACE","expansionScope":{"type":"WORKSPACE"},"output":{"type":"SYMBOLS","fields":["NAME","LOCATION","SIGNATURE"]}},"retention":"RETAIN","executionBudget":{"maxResults":20}}}
```

Accepted references: Issued exact symbols in SYMBOL_REFS.
Returned capabilities: Grouped symbols, connections, and occurrences.
Legal next actions: Preserve completion and omissions. Read retained-evidence.md.

Canonical choices and defaults:
- `Trace.type`: choices: `["TRACE"]`, default: `"TRACE"`.
- `SymbolsOutput.type`: choices: `["SYMBOLS"]`, default: `"SYMBOLS"`.
- `SymbolsOutput.fields`: choices: `["NAME","LOCATION","SIGNATURE","SOURCE"]`.

COMMON outputs: `SYMBOLS`, `OCCURRENCES`. TRACE accepts `SYMBOLS` only.

Read `../query-schema.json` for complete shapes and constraints.
