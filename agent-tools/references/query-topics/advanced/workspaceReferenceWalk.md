# workspaceReferenceWalk

Illustrative one-hop walk with explicit workspace expansion independent of the selected symbol scope.

```json
{"request":{"type":"RUN","source":{"type":"SYMBOL_REFS","symbolRefs":["exact:v5:AAAAAAAAAAAAAAAAAAAAAQ"]},"steps":[{"type":"WALK","relation":"REFERENCES","maximumDepth":1,"expansionScope":{"type":"WORKSPACE"}}],"terminalStep":{"type":"EMIT","output":{"type":"TRAVERSAL_RECORDS"}},"retention":"RETAIN"}}
```
