# Architecture Gradle adapter proof

This directory owns executable proof for architecture task boundaries. Keep raw Gradle/file inputs
at these fixtures and assert their finite refinement or task failure before any outer rendering.

Run the named focused test class, then `./gradlew -p build-logic test`.
