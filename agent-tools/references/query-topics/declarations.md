# declarations

Find an interface by its simple name and Kotlin package. Interfaces use the CLASS declaration family.

```json
{"request":{"type":"RUN","source":{"type":"SEARCH_DECLARATIONS","declarationName":"OrderRepository","declarationKinds":["CLASS"],"scope":{"type":"PACKAGE","packageName":"com.example.orders","includeSubpackages":false,"sourceSetNames":["main"]}},"terminalStep":{"type":"EMIT","output":{"type":"SYMBOLS","fields":["NAME","LOCATION","SIGNATURE"]}},"executionBudget":{"maxResults":20}}}
```

Accepted references: None.
Returned capabilities: Exact symbols.
Legal next actions: Select the exact owner, file, and signature. Read exact-selection.md.

Canonical choices and defaults:
- `SearchSource.type`: choices: `["SEARCH_DECLARATIONS"]`, default: `"SEARCH_DECLARATIONS"`.
- `SearchSource.nameMatch`: choices: `["EXACT","FUZZY",null]`.
- `SearchSource.declarationKinds`: choices: `["CLASS","FUNCTION","PROPERTY","TYPE_ALIAS"]`.
- `SymbolsOutput.type`: choices: `["SYMBOLS"]`, default: `"SYMBOLS"`.
- `SymbolsOutput.fields`: choices: `["NAME","LOCATION","SIGNATURE","SOURCE"]`.

COMMON outputs: `SYMBOLS`, `OCCURRENCES`. TRACE accepts `SYMBOLS` only.

Read `../query-schema.json` for complete shapes and constraints.
