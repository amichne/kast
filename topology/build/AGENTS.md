# Topology build module guide

`:topology:build` owns the sole explicit build coordinator and the private build authority. It
performs no IntelliJ, K2, JDBC, filesystem, Gradle, startup, or workspace-publication effect.

## Invariants

- Exact-identity reuse consumes only the snapshot store's current durable eligibility proof before
  candidate enumeration. A stale snapshot may be rebound without K2 only when the semantic source
  state is unchanged and current enumeration proves an identical canonical file set; edited files
  proceed to extraction.
- The coordinator enumerates only through the admitted-source-root port and accepts publication
  only after every candidate returns exact `CompleteTopologyFile` evidence. An extractor returning
  different same-path evidence reaches the typed coverage rejection and never publication.
- Before publication, the coordinator re-enumerates the admitted source roots and requires the
  exact candidate paths, source-set ownership, and content hashes to remain unchanged. Any drift
  is a typed coverage failure and cannot publish the earlier extraction.
- Publication runs only while the original semantic lease remains current. Failure and
  cancellation never invoke a later publication step.
- Startup, workspace reconciliation, reads, and traversal cannot construct this module's private
  build authority.

## Verification

1. Run `./gradlew :topology:build:test`.
2. Run `./gradlew :topology:contract:test :topology:build:test :evidence:sqlite:test`.
3. Run `./gradlew verifyTopologyAuthority verifyKastModuleGraph verifyForbiddenEffects`.
