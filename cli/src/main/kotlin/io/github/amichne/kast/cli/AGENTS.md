# `cli/src/main/kotlin/io/github/amichne/kast/cli` guide

This directory owns production sources under `cli/src/main/kotlin/io/github/amichne/kast/cli`. Follow [the nearest owner guide](../../../../../../../../AGENTS.md) for boundaries, invariants, and verification.

## Local scope

- `runtime/IdeEndpointAdmission.kt` owns the raw descriptor-to-compatible exact-root reachable
  endpoint transition and preserves that proof in `AdmittedIdeEndpoint`.
- Filesystem, process, and UDS probes remain injected outer capabilities with closed outcomes; no
  downstream caller receives raw descriptor text or authority to scan endpoint locations.
