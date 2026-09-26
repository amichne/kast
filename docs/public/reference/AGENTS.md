# Public reference boundaries

- The MCP and tool RPC contract pages and transport model pages are authored content.
- `callables.openapi.json` is generated from the canonical CLI command graph and installed hosted
  schemas plus direct MCP and tool RPC result types. Never edit it directly.
- Update the callable artifact with `./gradlew :cli:generateMintlifyCallableReference` from the
  repository root.
- Run `./gradlew :cli:verifyMintlifyCallableReference` to reject projection drift, then run
  `mint validate` from `docs/public` to verify Mintlify can render the generated OpenAPI document.
