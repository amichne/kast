# IntelliJ plugin descriptor owner

This directory owns the packaged `plugin.xml` registrations for the standalone Kast IDE plugin.

- Register the preloaded project-scoped `IdeEndpointService` and its post-startup activity here.
- Preserve the transitional legacy starter and Gradle resolver until their canonical delivery task
  removes them; KVP-024 must not make either path the hosted endpoint default.
- Keep implementation class names aligned with the module-owned plugin JAR and verify the physical
  descriptor through the endpoint publication tests and `:ide-plugin:buildPlugin`.
