# `implementations`

Find distinct implementations of an exact declaration. Replace the illustrative exact-ref placeholder with an issued ref.

```json
{"request":{"type":"RUN","source":{"type":"SYMBOL_REFS","symbolRefs":["<exact-ref-returned-by-Kast>"]},"steps":[{"type":"EXPAND_RELATION","relation":"IMPLEMENTATIONS"},{"type":"DISTINCT_SYMBOLS"}],"terminalStep":{"type":"EMIT","output":{"type":"SYMBOLS","fields":["NAME","LOCATION","SIGNATURE"]}},"executionBudget":{"maxResults":50}}}
```
