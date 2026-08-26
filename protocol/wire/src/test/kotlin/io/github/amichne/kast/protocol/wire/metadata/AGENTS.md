# Endpoint metadata tests

This directory owns public-behavior tests for the IDE endpoint descriptor and its deterministic
KVP-013 projection.

- Exercise descriptor admission through `IdeEndpointDescriptorV2`; do not test codec internals.
- Negative cases must assert closed typed failures for malformed, stale, ambiguous, incompatible,
  or under-specified documents.
- The positive projection test must read the Gradle-generated report and prove it is the canonical
  admitted descriptor document.

Run `./gradlew :protocol:wire:test --tests '*IdeEndpointDescriptorNegativeTest' --tests
'*IdeEndpointDescriptorTest'`.
