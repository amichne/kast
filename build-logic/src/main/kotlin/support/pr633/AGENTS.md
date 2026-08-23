# PR 633 verification task types

This directory owns reusable, cache-aware Gradle task types for exact-head stack admission,
caller-scoped bytecode authority checks, stable topology-contract inspection, and deterministic
gate evidence. Repository-specific task wiring and paths remain under `gradle/pr633/`.

Stack admission permits a changed `AGENTS.md` only when the guide covers another changed
non-guide path and its prefix is named by the checked PR policy.

Run `./gradlew -p build-logic test` after changing these types, then run the consuming PR 633
gate task from the repository root.
