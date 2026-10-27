# narrowReferenceExpansion

Illustrative exact reference with native expansion restricted to one imported source set and directory. Replace the handle with an issued current-basis reference; the directory restricts destinations, not seed selection.

```json
{"request":{"type":"RUN","source":{"type":"SYMBOL_REFS","symbolRefs":["exact:v5:AAAAAAAAAAAAAAAAAAAAAQ"]},"steps":[{"type":"EXPAND_RELATION","relation":"REFERENCES","expansionScope":{"type":"SOURCE_DOMAIN","sourceSets":["main"],"directory":"logging/src/main/kotlin/representation/fixture","includeSubdirectories":false,"sourcePolicy":"PRODUCTION_ONLY","generatedSources":"EXCLUDE"}}],"terminalStep":{"type":"EMIT","output":{"type":"OCCURRENCES"}},"retention":"RETAIN"}}
```
