# Workspace IntelliJ adapter tests

This package owns adapter tests for import observation, module materialization, JVM selection, and
workspace reconciliation. Gradle source-root provenance tests have their narrower child owner.

- Use detached project-model fixtures where possible and the matched IntelliJ test runtime where
  platform services are required.
- Assert closed readiness/import failures and exact module/source-root evidence; do not repair a
  foreground IDE project in tests.

Run `./gradlew :workspace:intellij:test`.
