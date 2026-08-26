# Standalone plugin build authority

This package owns build-time refinement and deterministic assembly of the standalone Kast IntelliJ
plugin artifact.

- Refine staged JAR observations into `ValidatedStandalonePluginPayload` before writing an archive.
- Require one exact descriptor identity, application-starter registration, and Gradle resolver
  registration.
- Reject private IDEA-home paths, duplicate entries, malformed JARs or descriptors, and directly
  observed platform-owned classes as finite `StandalonePluginFailure` data.
- Emit KVP-010 reports through generated serializers and bind every payload and artifact digest.

This is build-only policy. Product runtime behavior remains in the consuming modules.
