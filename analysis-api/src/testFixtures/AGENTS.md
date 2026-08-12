# Analysis API test-fixtures guide

This is the most specific Gradle guidance for
`analysis-api/src/testFixtures`. The source set is published as the
`analysis-api` test-fixtures variant and lets downstream modules exercise the
shared contract without starting a transport, SQLite store, or IntelliJ host.
The parent [module guide](../../AGENTS.md) still applies.

## Local map

- `AnalysisBackendContractFixture` and `AnalysisBackendContractAssertions`
  define reusable backend conformance scenarios.
- `FakeAnalysisBackend` is the configurable in-memory `AnalysisBackend`.
  Factory, query, mutation, continuation, and workspace-file behavior is split
  into the neighboring `FakeAnalysis*` files.
- `InMemoryFileOperations` owns Jimfs-backed descriptor and edit fixtures.
- `RelationshipCoverageFixture` is the compiler-visible relationship anchor.

## Dependency boundary

- Keep this source set test-only in meaning even though Gradle exposes it to
  downstream tests.
- Depend on the public `analysis-api` contract and deterministic test
  libraries only. Production transport belongs to `analysis-server`,
  persistence belongs to `index-store`, and IntelliJ behavior belongs to
  `indexer`.
- A fake may model only behavior required by a contract test. Do not turn it
  into a second backend implementation or copy production orchestration into
  fixtures.

## Local invariants

- Keep file contents, paths, offsets, hashes, generations, capability sets,
  page boundaries, and continuation identities deterministic.
- Keep fake continuation state single-use and query-bound when the public
  contract is single-use and query-bound.
- Preserve exact mutation evidence. Fake planning and application must retain
  the same hashes, semantic generation, signatures, ownership, cardinality,
  and postcondition shape that consumers assert.
- Extend the contract fixture and assertions when `AnalysisBackend` gains a
  required behavior. Add optional fake behavior only for a consuming test.
- Use Jimfs for filesystem behavior unless a test explicitly proves a real
  platform boundary.

## Verification ladder

1. Run the focused consuming test while iterating, for example:
   `./gradlew :analysis-server:test --tests '<fully.qualified.TestClass>'`.
2. Run `./gradlew :analysis-server:test`; it is the direct consumer of the
   published test-fixtures variant.
3. If shared backend semantics changed, run `./gradlew :analysis-api:test` and
   the affected `:indexer:test` class.
4. Use `./gradlew test` only when the fixture change affects more than one
   consumer.
