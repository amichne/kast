# Architecture authority guide

This directory owns the typed module graph, compiled JVM effect classification, and architecture
admission used by the root build.

## Local scope

- `ArchitectureModel.kt` owns module, role, lifecycle, and finite effect identities.
- `JvmEffectScanner.kt` extracts effects from compiled class files. Role-specific rules must remain
  scoped to that role so legitimate effect owners are not reclassified. KVP-018 hashes and scans
  the same immutable class-byte observation.
- `IdeReadFirewall.kt` derives the KVP-009 proof for the three `IDE_READ_ONLY` modules, their
  monotonic materialization stage, and nine fixed forbidden authorities.
- `IdeReadFirewallReport.kt` owns the generated closed report schema; fixed module policies and
  forbidden authorities are dedicated documents rather than map-shaped extension slots. Advance
  its schema version when a later delivery task adds required evidence.
- Policy construction, validation, projection, and Gradle effects remain in their named child
  owners; do not duplicate those rules at the task boundary.
- `policy/HostedRead*` owns the fixed 120-authority ASM negative contract, complete hosted-class,
  exact project-artifact closure, exact external-artifact byte admission, and canonical
  predecessor-bound KVP-018 report.
- `validation/NoDefaultRuntimeFallback.kt` owns the KVP-027 transitive installed-composition bytecode closure
  and its five closed fallback-authority mappings. Keep unreachable legacy fixtures outside the
  admitted closure; never weaken the entrypoint or turn a forbidden mapping into text inference.

Run focused architecture tests, then `verifyKastArchitectureProjection` (the aggregate for
`verifyKastModuleGraph` and `verifyForbiddenEffects`) and the two `verifyKastVfsPassiveFirewall*`
tasks.
