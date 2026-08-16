# Diagnostic service module guide

`:diagnostic:service` owns current-generation admission and revalidation for public
`diagnostic.check`. It does not own compiler execution, IntelliJ, workspace transitions,
persistence, transport, or mutation.

## Invariants

- Admit the request lease against the sole ready workspace before compiler work.
- Revalidate the exact root and generation after compiler work; movement discards all evidence.
- Accept compiler output only when its exact scope and coverage belong to the original request.
- Expected rejection is closed typed data.

## Verification ladder

1. Run `./gradlew :diagnostic:service:test`.
2. Run `./gradlew :diagnostic:service:test :diagnostic:intellij:test`.
