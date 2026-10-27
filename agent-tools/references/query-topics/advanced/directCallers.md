# directCallers

Find distinct declarations calling an exact function. Replace the illustrative exact-ref placeholder with an issued ref.

```json
{"request":{"type":"RUN","source":{"type":"SYMBOL_REFS","symbolRefs":["<exact-ref-returned-by-Kast>"]},"steps":[{"type":"EXPAND_RELATION","relation":"CALLERS"},{"type":"DISTINCT_SYMBOLS"}],"terminalStep":{"type":"EMIT","output":{"type":"SYMBOLS","fields":["NAME","LOCATION","SIGNATURE"]}},"executionBudget":{"maxResults":50}}}
```
