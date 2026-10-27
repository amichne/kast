# `scopedExactDeclaration`

Find a class by exact simple name in one package and source set, including its committed source context.

```json
{"request":{"type":"RUN","source":{"type":"SEARCH_DECLARATIONS","declarationName":"OrderService","declarationKinds":["CLASS"],"scope":{"type":"PACKAGE","packageName":"com.example.orders","includeSubpackages":false,"sourceSetNames":["main"]}},"terminalStep":{"type":"EMIT","output":{"type":"SYMBOLS","fields":["NAME","LOCATION","SIGNATURE","SOURCE"]}},"executionBudget":{"maxResults":20}}}
```
