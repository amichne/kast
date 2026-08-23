# Runtime server module guide

`:runtime:server` owns contract-only typed dispatch for one protocol wire request frame.

## Dependency boundary

- Production depends only on `:protocol:contract`, `:protocol:registry`, and `:protocol:wire`;
  kernel and serialization contracts remain transitive through those public APIs.
- Do not import IntelliJ, Gradle, JDBC, filesystem mutation, source writers, service
  implementations, platform adapters, or aggregate backend types.
- Runtime composition supplies typed operation handlers; this module cannot construct the complete
  implementation graph.

## Contract invariants

- Construction admits exactly one typed handler binding for every canonical operation.
- Dispatch admits the wire request once, routes by its canonical operation, and lets only the
  matching typed wire binding decode the retained payload.
- Complete, qualified, and rejected semantic outcomes all return through the canonical wire
  envelope.
- Expected admission, decoding, and encoding failures are closed data.

## Verification ladder

1. Run `./gradlew :runtime:server:test --tests '*RuntimeServerContractTest'`.
2. Run `./gradlew :runtime:server:test verifyKastModuleGraph`.
3. Run `./gradlew verifyKastModuleGraph verifyForbiddenEffects` after architecture admission.
