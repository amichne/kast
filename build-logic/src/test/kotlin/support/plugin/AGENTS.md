# Standalone plugin policy tests

This directory owns focused tests for build-time standalone plugin payload admission.

- Prove the exact descriptor and registrations refine to `ValidatedStandalonePluginPayload`.
- Keep private IDEA-home, platform-class, descriptor-cardinality, identity, and registration
  failures exhaustive over `StandalonePluginFailure` cases relevant to KVP-010.
- Decode the generated KVP-010 report as a closed schema and independently bind its physical ZIP,
  entry set, sizes, digests, and single descriptor owner.
- Prove the KVP-012 report digest changes when physical registry bytes change, always binds the
  sole canonical wire-schema bytes, and admits only against the matching physical registry.

Run `./gradlew :build-logic:test --tests 'support.plugin.StandalonePlugin*Test'` before the full
build-logic suite and the consuming `:ide-plugin:buildPlugin` task. Run
`./gradlew -p build-logic test --tests 'support.plugin.GenerateIdeHostCompatibilityReportTaskTest'`
for the focused KVP-012 report proof.
