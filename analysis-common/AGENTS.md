# Analysis common agent guide

`analysis-common` owns PSI and K2 Analysis API helpers shared by both runtime
hosts.

## Ownership

Keep this unit focused on shared semantic utilities, not host bootstrapping.

- Keep reusable PSI and K2 Analysis API helpers here, including symbol
  resolution helpers, location conversion, and diagnostic mapping shared by
  `backend-intellij` and `backend-standalone`.
- This module may compile against IntelliJ PSI and Kotlin Analysis API types,
  but those dependencies are runtime-provided by the hosts. Do not add plugin
  lifecycle wiring, Ktor transport, or CLI behavior here.
- Preserve host-neutral behavior. A helper in this module must behave the same
  whether the caller is the IntelliJ backend or the standalone backend.
- Keep contract-facing semantics aligned with `analysis-api`. If symbol,
  diagnostic, or location models move, update the callers and tests together.
- If a change needs project startup, threading rules, or host capability
  decisions, move that work back to the owning `backend-*` unit.

## Verification

Validate this shared layer before relying on downstream failures.

- Run `./gradlew :analysis-common:build`.
- If Gradle cannot find the IntelliJ distribution in cache, run
  `./gradlew :backend-intellij:build` once, then rerun
  `./gradlew :analysis-common:build`.
- If you change shared symbol or diagnostic behavior, also run the affected
  backend module builds or tests.
