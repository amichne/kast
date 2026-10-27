# scopedTextWord

Find functions containing the indexed word launchd under app-server; inspect or traverse each exact returned ref.

```json
{"request":{"type":"RUN","source":{"type":"SEARCH_TEXT","word":"launchd","scope":{"type":"DIRECTORY","relativeDirectoryPath":"app-server","sourceSetNames":["main"]},"declarationKinds":["FUNCTION"]},"terminalStep":{"type":"EMIT","output":{"type":"SYMBOLS","fields":["NAME","LOCATION"]}},"executionBudget":{"maxResults":10}}}
```
