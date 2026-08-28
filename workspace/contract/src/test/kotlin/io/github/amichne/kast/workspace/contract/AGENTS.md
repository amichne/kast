# Workspace contract test guide

This directory owns executable contract proofs for workspace identities, opaque project-read
epochs, source-root admission, search-scope compilation, and resource policy.

## Test invariants

- Assert the exact closed rejection for invalid roots, paths, limits, percentages, and counts.
- Prove that imported model ownership and provenance determine search scope. Incomplete, unknown,
  ambiguous, or incoherent model evidence must fail closed.
- Preserve platform-sensitive path identity regressions, including a Unix filename that contains a
  literal backslash.
- Use refinement-unwrapping helpers only after a test has supplied an input intended to be valid.
- Prove that epochs expose no source or signal state, compare only within one source instance, and
  never repeat the source observation during comparison.

## Focused verification

Run `./gradlew :workspace:contract:test --tests '*ProjectReadEpochNegativeTest'` and
`./gradlew :workspace:contract:test --tests '*ProjectReadEpochTest'` for epoch changes. Run
`./gradlew :workspace:contract:test --tests '*SourceRootAdmissionTest'` for source-root admission
changes, then run `./gradlew :workspace:contract:test` for the package contract.
