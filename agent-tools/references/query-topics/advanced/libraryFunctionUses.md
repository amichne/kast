# `libraryFunctionUses`

Find workspace uses of every overload of one dependency function; select one overload first to separate them.

```json
{"request":{"type":"RUN","source":{"type":"SEARCH_LIBRARY","declarationName":"listOfNotNull","packageName":"kotlin.collections"},"steps":[{"type":"EXPAND_RELATION","relation":"REFERENCES"}],"terminalStep":{"type":"EMIT","output":{"type":"OCCURRENCES"}}}}
```
