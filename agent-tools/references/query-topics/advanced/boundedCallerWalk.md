# `boundedCallerWalk`

Trace callers for two hops with depth-bearing records. Replace the illustrative exact-ref placeholder with an issued ref.

```json
{"request":{"type":"RUN","source":{"type":"SYMBOL_REFS","symbolRefs":["<exact-ref-returned-by-Kast>"]},"steps":[{"type":"WALK","relation":"CALLERS","maximumDepth":2}],"terminalStep":{"type":"EMIT","output":{"type":"TRAVERSAL_RECORDS"}},"executionBudget":{"maxResults":50}}}
```
