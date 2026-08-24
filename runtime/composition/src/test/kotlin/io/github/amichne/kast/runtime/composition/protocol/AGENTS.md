# Runtime composition protocol test guide

This package owns composition-level tests for durable selector authority and typed public protocol
projections.

## Invariants

- Selector documents remain accepted after protocol-authority recreation.
- Topology failures retain structured candidate, file, workspace, source-root, and endpoint
  evidence when projected to public protocol documents.
- Tests exercise generated protocol documents and domain types, not map-shaped JSON surrogates.

## Verification

Run `./gradlew :runtime:composition:test --tests '*CanonicalProtocolAuthorityDurabilityTest' --tests '*TopologyCoverageProjectionTest'`.
