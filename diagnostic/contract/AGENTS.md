# Diagnostic contract module guide

`:diagnostic:contract` owns detached host-neutral requests, facts, coverage, outcomes, and the
compiler-read port for `diagnostic.check`. It does not own IntelliJ, workspace transitions,
persistence, traversal, transport, or mutation.

## Invariants

- One diagnostic scope is a non-empty canonical set of Kotlin files below one exact workspace
  root and is permanently bound to one semantic read lease.
- Every fact carries a typed severity, code, message, range, scope file, and the scope generation.
- Complete coverage accounts for every exact scope file. Any unavailable or unproven file makes
  the result qualified; empty incomplete evidence never proves absence.
- Contract values are immutable detached data and retain no live host object or callback.

## Verification ladder

1. Run `./gradlew :diagnostic:service:test :diagnostic:intellij:test`.
2. Run `./gradlew verifyKastModuleGraph verifyForbiddenEffects` after architecture admission.
