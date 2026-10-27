# fuzzyDeclarationDiscovery

Opt into fuzzy simple-name discovery under a known directory, then select the returned exact declaration.

```json
{"request":{"type":"RUN","source":{"type":"SEARCH_DECLARATIONS","declarationName":"OrderServ","nameMatch":"FUZZY","declarationKinds":["CLASS","FUNCTION"],"scope":{"type":"DIRECTORY","relativeDirectoryPath":"services/orders","sourceSetNames":["main"]}},"terminalStep":{"type":"EMIT","output":{"type":"SYMBOLS","fields":["NAME","LOCATION","SIGNATURE"]}},"executionBudget":{"maxResults":20}}}
```
