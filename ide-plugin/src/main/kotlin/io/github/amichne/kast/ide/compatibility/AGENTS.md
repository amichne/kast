# IDE compatibility metadata boundary

This directory owns the IDE-plugin adapter from the generated KVP-012 report to the host-neutral
compatibility contract.

- Decode the closed report schema through its generated `serializer()` and one strict `Json`
  instance.
- Keep the adapter module-internal so `:ide-plugin` does not export `:protocol:contract` types.
- Refine decoded primitives through `IdeHostCompatibilityPolicy`; malformed schema, task identity,
  and compatibility failures remain finite typed data.
- Extract raw JSON and field strings only in this adapter.

Run `./gradlew :ide-plugin:generateIdeHostCompatibilityReport :ide-plugin:test --tests
'*IdeHostCompatibilityTest' --tests '*IdeHostCompatibilityNegativeTest'`.
