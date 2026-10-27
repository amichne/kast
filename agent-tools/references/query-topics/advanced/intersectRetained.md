# `intersectRetained`

Intersect two retained symbol results by canonical identity. Replace both non-issued illustrative result handles.

```json
{"request":{"type":"RUN","source":{"type":"RESULT","result":"result:v1:00000000-0000-0000-0000-000000000000"},"steps":[{"type":"INTERSECT","right":{"type":"RESULT","result":"result:v1:00000000-0000-0000-0000-000000000001"}}],"terminalStep":{"type":"EMIT","output":{"type":"SYMBOLS","fields":["NAME","LOCATION","SIGNATURE"]}}}}
```
