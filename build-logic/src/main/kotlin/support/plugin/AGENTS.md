# Standalone plugin build authority

This package owns build-time refinement and deterministic assembly of the standalone Kast IntelliJ
plugin artifact.

- Refine staged JAR observations into `ValidatedStandalonePluginPayload` before writing an archive.
- Require one exact descriptor identity, application-starter registration, and Gradle resolver
  registration.
- Reject private IDEA-home paths, duplicate entries, malformed JARs or descriptors, and directly
  observed platform-owned classes as finite `StandalonePluginFailure` data.
- Emit KVP-010 reports through generated serializers. Decode them through the same closed document,
  refine every reported identity, path, size, and digest, then independently re-observe the exact
  physical archive before exposing `VerifiedStandalonePluginReport` to receipt progression.
- Own KVP-011 hosted-plugin layout admission and fixed negative fixtures here. Preserve admitted
  digests, byte sizes, and class owners as domain types until the generated-report boundary, and
  classify policy observations through closed permitted or rejected results.
- Keep Gradle file boundaries, task outcome projection, and the generated report document with the
  task wrapper.
- Scan nested class definitions and effect-bearing bytecode references. Reject platform-owned,
  bootstrap, mutation, topology, JDBC, runtime-acquisition, process-launch, JBR, and native-runtime
  payloads through the closed `IdePluginLayoutFailure` set.
- Generate the KVP-012 compatibility report from declared hosted pins and exact registry and wire
  bytes. Keep the report document on generated kotlinx serialization, admit its exact physical
  projections for receipt evidence, and leave product compatibility semantics to the contract.

This is build-only policy. Product runtime behavior remains in the consuming modules.
