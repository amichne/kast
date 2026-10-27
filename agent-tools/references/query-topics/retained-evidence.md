# retained-evidence

Present retained symbol rows without new semantic evaluation. Replace the non-issued all-zero result handle.

```json
{"request":{"type":"READ_RESULT","result":"result:v1:00000000-0000-0000-0000-000000000000","output":{"type":"SYMBOLS","fields":["NAME","LOCATION","SIGNATURE"]},"executionBudget":{"maxResults":20}}}
```

Accepted references: Issued result reference and its cursors.
Returned capabilities: Presentation pages on the original basis.
Legal next actions: Use returned cursors with the same result. Never replay RUN to recover evidence.

The evidence cursor advances over presentation positions, including summaries.
A deferred summary does not deliver its evidence.
`DEFERRED_DETAIL` means that detail remains unread after presentation ends.
Copy the issued selector into `READ_RESULT.detail`.

Detail offsets, ends, and totals count UTF-16 code units in the canonical entry.
SHA-256 covers the complete serialized entry as UTF-8 bytes.

```json
{"request":{"type":"READ_RESULT","result":"result:v1:00000000-0000-0000-0000-000000000000","detail":{"result":"result:v1:00000000-0000-0000-0000-000000000000","section":"CANONICAL_ENTRY_JSON","target":"ROW","ordinal":0,"kind":"EXACT_SYMBOL","sha256":"0000000000000000000000000000000000000000000000000000000000000000","offset":0}}}
```

Canonical choices and defaults:
- `ReadResultAction.type`: choices: `["READ_RESULT"]`, default: `"READ_RESULT"`.

COMMON outputs: `SYMBOLS`, `OCCURRENCES`. TRACE accepts `SYMBOLS` only.

Read `../query-schema.json` for complete shapes and constraints.
