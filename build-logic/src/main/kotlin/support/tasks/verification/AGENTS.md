# Build verification task policy

This package owns reusable Gradle task types that verify distribution layout and generated
serialization sources. Keep these tasks deterministic over declared inputs and return failures
through Gradle task outcomes at the build boundary.

Run `./gradlew -p build-logic test` after changing their contracts.
