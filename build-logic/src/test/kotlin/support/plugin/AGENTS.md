# Standalone plugin policy tests

This directory owns focused tests for build-time standalone plugin payload admission.

- Prove the exact descriptor and registrations refine to `ValidatedStandalonePluginPayload`.
- Keep private IDEA-home, platform-class, descriptor-cardinality, identity, and registration
  failures exhaustive over `StandalonePluginFailure` cases relevant to KVP-010.

Run `./gradlew :build-logic:test --tests '*StandalonePluginModelTest'` before the full build-logic
suite and the consuming `:ide-plugin:buildPlugin` task.
