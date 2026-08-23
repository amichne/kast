# PR 633 verification task types

This directory owns reusable, cache-aware Gradle task types for exact-head stack admission,
caller-scoped bytecode authority checks, stable topology-contract inspection, and deterministic
gate evidence. Repository-specific task wiring and paths remain under `gradle/pr633/`.

`api/` owns the exact compiled topology-contract ABI projection and checked-manifest comparison.

Stack admission derives its allow-list from the program task scopes. It permits a changed
`AGENTS.md` only when the program grants ancestor-guide authority and the guide covers another
admitted non-guide path; the separate path policy remains a deny-list only.

Git-reading gates resolve mutable refs to typed SHAs, execute against that exact range, and remain
untracked by Gradle so repository-state proof cannot be reused as up-to-date. Internal Kotlin class
claims use strict compiler metadata visibility plus exact source and compiled identity; JVM access
flags alone cannot prove Kotlin `internal`.

Run `./gradlew -p build-logic test` after changing these types, then run the consuming PR 633
gate task from the repository root.
