# Topology build module guide

`:topology:build` owns the sole explicit build coordinator and the private build authority. It
performs no IntelliJ, K2, JDBC, filesystem, Gradle, startup, or workspace-publication effect.

## Invariants

- Exact-identity reuse is checked before candidate enumeration. A stale snapshot may be rebound
  without K2 only after current source-root enumeration proves an identical canonical file set;
  edited files proceed to extraction.
- The coordinator enumerates only through the admitted-source-root port and accepts publication
  only after every candidate returns `CompleteTopologyFile`.
- Publication runs only while the original semantic lease remains current. Failure and
  cancellation never invoke a later publication step.
- Startup, workspace reconciliation, reads, and traversal cannot construct this module's private
  build authority.

## Verification

1. Run `./gradlew :topology:build:test`.
2. Run `./gradlew :topology:contract:test :topology:build:test :evidence:sqlite:test`.
3. Run `./gradlew verifyTopologyAuthority verifyKastModuleGraph verifyForbiddenEffects`.
