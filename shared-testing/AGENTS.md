# Shared testing agent guide

`shared-testing` exists to help tests exercise the public contract without
bringing in a real host backend.

## Ownership

Keep this unit deterministic so downstream tests stay readable and stable.

- Keep this unit test-only in spirit, even though Gradle publishes it as a
  normal module for reuse in tests.
- Own fake backends, deterministic fixture files, and helpers that make server
  and backend tests easier to read.
- Do not add production-only behavior, network servers, or IntelliJ Platform
  dependencies here.
- Keep fixtures small and explicit. Stable offsets, file contents, and
  capability sets matter because downstream tests depend on them.
- Mirror the public API closely enough for tests to be meaningful, but avoid
  simulating more behavior than the tests need.

## Verification

Check the consumers of these fixtures, not just the fixture code itself.

- Run the consuming tests that rely on this unit, starting with
  `./gradlew :analysis-server:test`.
- Broaden the scope if you change shared fixture semantics.
