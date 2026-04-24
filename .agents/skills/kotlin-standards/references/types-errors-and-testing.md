# Errors And Testing

Use for expected failures, typed outcomes, and correctness-focused tests.

- Expected failures should be explicit, typed, and assertable.
- Use the repository's existing error ADT, result wrapper, or exception contract
  first.
- If no local pattern exists, prefer Kotlin `Result` before inventing a generic
  wrapper.
- Test public behavior: valid parse, invalid parse, state transition, boundary
  failure, ordering, idempotence, compatibility, and cancellation.
- Add tracer bullets one behavior at a time.
- Do not add tests only to raise coverage.
