# Analysis API test fixtures agent guide

`analysis-api` test fixtures help tests exercise the public contract without
bringing in a real host backend.

## Ownership

Keep this unit deterministic so downstream tests stay readable and stable.

- Keep this unit test-only in spirit, even though Gradle publishes it as the
  `analysis-api` test-fixtures variant for reuse in downstream tests.
- Own fake backends, deterministic fixture files, and helpers that make server
  and backend tests easier to read.
- Production host behavior, network servers, and IDEA Platform dependencies
  live in production modules.
- Keep fixtures small and explicit. Stable offsets, file contents, and
  capability sets matter because downstream tests depend on them.
- Mirror the public API closely enough for meaningful tests. Keep simulation
  bounded to the consuming tests.

## Verification

Check the consumers of these fixtures together with the fixture code.

- Run the consuming tests that rely on this unit, starting with
  `./gradlew :analysis-server:test`.
- Broaden the scope if you change shared fixture semantics.
