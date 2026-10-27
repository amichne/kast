# retainByName

Retain a bounded exact-name result for same-basis presentation or composition; inspect the retention outcome.

```json
{"request":{"type":"RUN","source":{"type":"SEARCH_DECLARATIONS","declarationName":"OrderService","declarationKinds":["CLASS"]},"terminalStep":{"type":"EMIT","output":{"type":"SYMBOLS","fields":["NAME","LOCATION","SIGNATURE"]}},"retention":"RETAIN","executionBudget":{"maxResults":20}}}
```
