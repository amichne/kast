# retained-evidence

Present retained symbol rows without new semantic evaluation. Replace the non-issued all-zero result handle.

```json
{"request":{"type":"READ_RESULT","result":"result:v1:00000000-0000-0000-0000-000000000000","output":{"type":"SYMBOLS","fields":["NAME","LOCATION","SIGNATURE"]},"executionBudget":{"maxResults":20}}}
```

Accepted references: Issued result reference and its cursors.
Returned capabilities: Presentation pages on the original basis.
Legal next actions: Use returned cursors with the same result. Never replay RUN to recover evidence.

Canonical choices and defaults:
- `ReadResultAction.type`: choices: ["READ_RESULT"], default: "READ_RESULT".

COMMON outputs: `SYMBOLS`, `OCCURRENCES`. TRACE accepts `SYMBOLS` only.

Read `../query-schema.json` for complete shapes and constraints.
