# exact-selection

Find the named declaration containing a known UTF-16 offset; adapt the illustrative relative file and offset.

```json
{"request":{"type":"RUN","source":{"type":"AT_LOCATION","file":"services/orders/src/main/kotlin/com/example/orders/OrderService.kt","offset":100},"terminalStep":{"type":"EMIT","output":{"type":"SYMBOLS","fields":["NAME","LOCATION","SIGNATURE","SOURCE"]}}}}
```

Accepted references: None for AT_LOCATION. SYMBOL_REFS needs issued exact symbols.
Returned capabilities: Exact symbols with identity and location.
Legal next actions: Reuse the issued exact ref unchanged. Read relations.md.

Canonical choices and defaults:
- `LocationSource.type`: choices: ["AT_LOCATION"], default: "AT_LOCATION".

COMMON outputs: `SYMBOLS`, `OCCURRENCES`. TRACE accepts `SYMBOLS` only.

Read `../query-schema.json` for complete shapes and constraints.
