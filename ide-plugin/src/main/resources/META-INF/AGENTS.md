# IntelliJ plugin descriptor owner

This directory owns the packaged `plugin.xml` registrations for the standalone Kast IDE plugin.

- Register the preloaded project-scoped `IdeEndpointService` and its post-startup activity here.
- Keep isolated application-starter and Gradle-resolver registrations absent from the hosted
  descriptor; their explicit legacy fixture is owned by `:indexer`.
- Keep implementation class names aligned with the module-owned plugin JAR and verify the physical
  descriptor through the endpoint publication tests and `:ide-plugin:buildPlugin`.
