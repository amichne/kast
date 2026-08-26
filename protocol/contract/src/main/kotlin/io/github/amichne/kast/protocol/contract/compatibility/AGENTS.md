# IDE host compatibility contract

This directory owns the KVP-012 proof transition from raw build-report identities to one exact
IDE-host compatibility tuple.

- Keep the six identity refinements and exact-match policy in `IdeHostCompatibility.kt`.
- Derive the four admitted read capabilities from `CanonicalOperation` in `IdeHostCapability.kt`.
- Preserve unknown, unsupported, malformed, duplicate, incomplete, reordered, and identity-mismatch
  states as closed `IdeHostCompatibilityFailure` data.
- Expose immutable admitted state. Raw strings and mutable collections remain boundary inputs only.

Run `./gradlew :protocol:contract:compileKotlin :ide-plugin:test --tests
'*IdeHostCompatibilityTest' --tests '*IdeHostCompatibilityNegativeTest'`.
