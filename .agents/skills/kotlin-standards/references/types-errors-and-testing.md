# Errors And Testing

Use for expected failures, typed outcomes, and correctness-focused tests.

- Expected failures should be explicit, typed, and assertable.
- Use the repository's existing error ADT, result wrapper, or exception contract
  first.
- If no local pattern exists, prefer Kotlin `Result` before inventing a generic
  wrapper.
- Prefer a sealed error hierarchy when callers branch on recovery. Prefer error
  accumulation when parsing can report multiple independent field failures.
- Test public behavior: valid parse, invalid parse, state transition, boundary
  failure, ordering, idempotence, compatibility, and cancellation.
- Add tracer bullets one behavior at a time.
- Do not add tests only to raise coverage.
- Name boundary tests by observable policy, such as `rejects unknown fields` or
  `defaults omitted timeout`, not by parser internals.
