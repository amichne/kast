# publicFunctionInventory

List public functions with a known name prefix in one module directory; strict execution proves the full answer before returning a bounded presentation.

```json
{"request":{"type":"RUN","source":{"type":"ALL_DECLARATIONS","declarationKinds":["FUNCTION"],"scope":{"type":"DIRECTORY","relativeDirectoryPath":"services/orders","sourceSetNames":["main"]}},"steps":[{"type":"WHERE","predicate":{"type":"VISIBILITY","values":["PUBLIC"]}},{"type":"WHERE","predicate":{"type":"PRIMITIVE","field":"NAME","operator":"STARTS_WITH","value":"create"}}],"terminalStep":{"type":"EMIT","output":{"type":"SYMBOLS","fields":["NAME","LOCATION","SIGNATURE"]}},"executionBudget":{"maxResults":50}}}
```
